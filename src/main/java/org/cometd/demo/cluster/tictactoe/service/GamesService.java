/*
 * Copyright (c) 2020-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.demo.cluster.tictactoe.service;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.server.Configure;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.demo.cluster.tictactoe.Game;
import org.cometd.oort.OortComet;
import org.cometd.oort.Seti;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service(GamesService.NAME)
public class GamesService {
    public static final String NAME = "games_service";
    private static final Logger LOGGER = LoggerFactory.getLogger(GamesService.class);
    private static final AtomicLong GAME_IDS = new AtomicLong();

    private final ConcurrentMap<String, ServerSession> _players = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Game> _newGames = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Game> _challengedGames = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Game> _liveGames = new ConcurrentHashMap<>();
    private final String node;
    @Inject
    private Seti seti;
    @Session
    private LocalSession _session;
    private volatile String migration;

    public GamesService(String node) {
        this.node = node;
    }

    @Configure({"/games", "/games/move", "/games/result"})
    public void configureBroadcastChannels(ConfigurableServerChannel channel) {
        channel.setPersistent(true);
    }

    @RemoteCall("/games/play")
    public void play(RemoteCall.Caller caller, Map<String, Object> data) {
        String player = (String)data.get("player");
        // Sanitize player input to avoid XSS.
        player = player.replaceAll("<", "_").replace(">", "_");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("player {}", player);
        }
        ServerSession session = caller.getServerSession();
        session.setAttribute("player", player);
        _players.put(player, session);
        session.addListener(dispose(player));
        caller.result(player);
        broadcastGameList();
    }

    @RemoteCall("/games/new")
    public void newGame(RemoteCall.Caller caller, Map<String, Object> data) {
        if (migration == null) {
            String gameId = node + "_" + GAME_IDS.incrementAndGet();
            ServerSession session = caller.getServerSession();
            String player = player(session);
            if (seti.getOort().isOort(session)) {
                // Trust the owner sent by other nodes.
                player = (String)data.get("player");
            }
            Game game = new Game(gameId, player);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("new game by player {}: {}", player, game);
            }
            _newGames.put(gameId, game);
            caller.result(game);
            broadcastGameList();
        } else {
            migrateNewGame(caller, data);
        }
    }

    private void migrateNewGame(RemoteCall.Caller caller, Map<String, Object> data) {
        ServerSession session = caller.getServerSession();
        String player = player(session);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("forwarding new game by player {} to {}", player, migration);
        }
        // Forward the game creation.
        OortComet comet = seti.getOort().getComet(migration);
        if (comet != null) {
            data.put("player", player);
            comet.remoteCall("/games/new", data, reply -> {
                if (reply.isSuccessful()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("new game forwarded successfully to {}", migration);
                    }
                    String url = migrationURL(player);
                    session.batch(() -> {
                        caller.result(reply.getData());
                        session.deliver(_session, "/service/games/migrate", url, Promise.noop());
                    });
                } else {
                    caller.failure(reply.getData());
                }
            });
        } else {
            // TODO:
        }
    }

    private String migrationURL(String player) {
        // TODO: hardcoded URL mangling (but also present in application.js).
        int index = migration.indexOf("/cometd");
        return migration.substring(0, index + 1) + "?player=" + URLEncoder.encode(player, UTF_8);
    }

    private ServerSession.RemoveListener dispose(String player) {
        return (s, t) -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("removing state for {}", player);
            }

            _players.remove(player);

            _newGames.values().removeIf(game -> game.owner().equals(player));
            _challengedGames.values().removeIf(game -> game.owner().equals(player));
            _liveGames.values().removeIf(game -> game.owner().equals(player));
        };
    }

    @RemoteCall("/games/find")
    public void findGame(RemoteCall.Caller caller, Map<String, Object> data) {
        String player = (String)data.get("player");
        Game game = findGame(player);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("found game {}: {}", player, game);
        }
        if (game != null) {
            caller.result(game);
            broadcastGameList();
        } else {
            caller.failure(player);
        }
    }

    private Game findGame(String player) {
        Game game = findGame(_newGames.values(), player);
        if (game != null) {
            return game;
        }
        game = findGame(_challengedGames.values(), player);
        if (game != null) {
            return game;
        }
        return findGame(_liveGames.values(), player);
    }

    private Game findGame(Collection<Game> games, String player) {
        return games.stream()
                .filter(game -> player.equals(game.owner()) || player.equals(game.opponent()))
                .findAny()
                .orElse(null);
    }

    private void broadcastGameList() {
        Collection<Game> games = new ArrayList<>(_newGames.values());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("broadcasting game list: {} games {}", games.size(), games);
        }
        ServerChannel channel = seti.getOort().getBayeuxServer().getChannel("/games");
        channel.publish(_session, games, Promise.noop());
    }

    @Listener("/service/games/challenge")
    public void handleChallenge(ServerSession session, ServerMessage message) {
        if (migration == null) {
            Map<String, Object> challenge = message.getDataAsMap();
            if ("request".equals(challenge.get("type"))) {
                handleChallengeRequest(session, message);
            } else {
                handleChallengeResponse(session, message);
            }
        } else {
            // TODO
        }
    }

    private void handleChallengeRequest(ServerSession session, ServerMessage message) {
        String gameId = (String)message.getDataAsMap().get("gameId");
        Game game = _newGames.remove(gameId);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("challenge request from {} for game {}", player(session), game);
        }
        if (game != null) {
            game.opponent(player(session));
            _challengedGames.put(gameId, game);
            // Send the challenge to the game owner.
            Map<String, Object> reply = new HashMap<>();
            reply.put("type", "request");
            reply.put("gameId", gameId);
            ServerSession owner = _players.get(game.owner());
            if (owner != null) {
                owner.deliver(session, message.getChannel(), reply, Promise.noop());
                broadcastGameList();
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("no session for game owner {}", game);
                }
            }
        } else {
            // TODO: no game, send back a message to the player
        }
    }

    private void handleChallengeResponse(ServerSession session, ServerMessage message) {
        Map<String, Object> challenge = message.getDataAsMap();
        String gameId = (String)challenge.get("gameId");
        boolean accepted = challenge.get("result") == Boolean.TRUE;
        if (accepted) {
            Game game = _challengedGames.remove(gameId);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("challenge response from {} for game {}", player(session), game);
            }
            if (game != null) {
                _liveGames.put(gameId, game);
                Map<String, Object> reply = new HashMap<>();
                reply.put("type", "response");
                reply.put("result", true);
                reply.put("game", game);
                ServerSession owner = _players.get(game.owner());
                ServerSession opponent = _players.get(game.opponent());
                if (owner != null) {
                    owner.deliver(opponent, message.getChannel(), reply, Promise.noop());
                } else {
                    // TODO:
                }
                if (opponent != null) {
                    opponent.deliver(owner, message.getChannel(), reply, Promise.noop());
                } else {
                    // TODO:
                }
            } else {
                // TODO: challenge accepted but no game, probably the opponent disconnected.
            }
        } else {
            // TODO: challenge not accepted.
        }
    }

    @Listener("/service/games/move")
    public void move(ServerSession session, ServerMessage message) {
        Game.Move move = new Game.Move();
        move.fromJSON(message.getDataAsMap());

        Game game = _liveGames.get(move.gameId);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("received move from {} for game {}: {}", player(session), game, move);
        }

        if (game != null) {
            if (game.move(move)) {
                if (migration == null) {
                    String player = player(session);
                    String otherPlayer = game.opponent();
                    if (player.equals(otherPlayer)) {
                        otherPlayer = game.owner();
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("sending move to {} for game {}: {}", otherPlayer, game, move);
                    }

                    // Broadcast the move.
                    ServerChannel moveChannel = seti.getOort().getBayeuxServer().getChannel("/games/move");
                    moveChannel.publish(_session, move, Promise.noop());

                    if (game.complete()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("game complete {}", game);
                        }
                        // Broadcast the result.
                        ServerChannel resultChannel = seti.getOort().getBayeuxServer().getChannel("/games/result");
                        resultChannel.publish(_session, game, Promise.noop());
                    }
                } else {
                    migrateMove(session, game);
                }
            } else {
                // TODO: invalid move.
            }
        } else {
            // TODO: no game
        }
    }

    private void migrateMove(ServerSession session, Game game) {
        String player = player(session);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("forwarding game by {} to {}: {}", player, migration, game);
        }

        OortComet comet = seti.getOort().getComet(migration);
        if (comet != null) {
            comet.remoteCall("/games/migrate/game", game, migrateGameReply -> {
                if (migrateGameReply.isSuccessful()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("forwarded game by player {} to {}: {}", player, migration, game);
                    }

                    // Remove the game.
                    _liveGames.remove(game.id());

                    // Disconnect current player.
                    String url = migrationURL(player);
                    session.deliver(_session, "/service/games/migrate", url, Promise.noop());

                    // Disconnect other player.
                    String otherPlayer = game.opponent();
                    if (player.equals(otherPlayer)) {
                        otherPlayer = game.owner();
                    }
                    ServerSession otherSession = _players.get(otherPlayer);
                    if (otherSession != null) {
                        url = migrationURL(otherPlayer);
                        otherSession.deliver(_session, "/service/games/migrate", url, Promise.noop());
                    } else {
                        // TODO:
                    }
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("could not forward game by player {} to {}: {} - {}", player, migration, game, migrateGameReply);
                    }
                }
            });
        } else {
            // TODO:
        }
    }

    @RemoteCall("/games/migrate/game")
    public void migrateGame(RemoteCall.Caller caller, Game game) {
        _liveGames.put(game.id(), game);
        caller.result(true);
    }

    @Listener("/service/games/migrate")
    public void migrate(ServerSession session, ServerMessage message) {
        Set<String> knownComets = seti.getOort().getKnownComets();
        if (!knownComets.isEmpty()) {
            this.migration = knownComets.iterator().next();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("initiating migration to {}", migration);
            }
        }
    }

    private static String player(ServerSession session) {
        return (String)session.getAttribute("player");
    }
}
