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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.demo.cluster.tictactoe.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service(GamesService.NAME)
public class GamesService {
    public static final String NAME = "games_service";
    private static final Logger LOGGER = LoggerFactory.getLogger(GamesService.class);
    private static final AtomicLong GAME_IDS = new AtomicLong();

    private final ConcurrentMap<String, GameWithOwner> _newGames = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GameWithOpponent> _challengedGames = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GameWithOpponent> _liveGames = new ConcurrentHashMap<>();
    @Inject
    private BayeuxServer _bayeuxServer;
    @Session
    private LocalSession _session;

    @RemoteCall("/games/play")
    public void play(RemoteCall.Caller caller, Map<String, Object> data) {
        String player = (String)data.get("player");
        // Sanitize player input to avoid XSS.
        player = player.replaceAll("<", "_").replace(">", "_");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("player {}", player);
        }
        caller.getServerSession().setAttribute("player", player);
        caller.result(player);
        broadcastGameList();
    }

    @RemoteCall("/games/new")
    public void newGame(RemoteCall.Caller caller, Map<String, Object> data) {
        String gameId = String.valueOf(GAME_IDS.incrementAndGet());
        ServerSession session = caller.getServerSession();
        String player = player(session);
        Game game = new Game(gameId, player);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("new game by player {}: {}", player, game);
        }
        GameWithOwner value = new GameWithOwner(game, session);
        _newGames.put(gameId, value);
        session.addListener(gameRemover(gameId));
        caller.result(game);
        broadcastGameList();
    }

    private ServerSession.RemoveListener gameRemover(String gameId) {
        return (s, t) -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("removing game {}", gameId);
            }
            _newGames.remove(gameId);
            _challengedGames.remove(gameId);
            // TODO: if one player goes away, need to send a messages to the other.
            _liveGames.remove(gameId);
        };
    }

    @RemoteCall("/games/get")
    public void getGame(RemoteCall.Caller caller, Map<String, Object> data) {
        String gameId = (String)data.get("gameId");
        Game game = findGame(gameId);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("get game {}: {}", gameId, game);
        }
        if (game != null) {
            caller.result(game);
            broadcastGameList();
        } else {
            caller.failure(gameId);
        }
    }

    private Game findGame(String gameId) {
        GameWithOwner gameWithOwner = _newGames.get(gameId);
        if (gameWithOwner != null) {
            return gameWithOwner.game;
        }
        GameWithOpponent gameWithOpponent = _challengedGames.get(gameId);
        if (gameWithOpponent != null) {
            return gameWithOpponent.gameWithOwner.game;
        }
        gameWithOpponent = _liveGames.get(gameId);
        if (gameWithOpponent != null) {
            return gameWithOpponent.gameWithOwner.game;
        }
        return null;
    }

    private void broadcastGameList() {
        Collection<Game> games = _newGames.values().stream()
                .map(gameWithOwner -> gameWithOwner.game)
                .collect(Collectors.toList());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("broadcasting game list: {} games {}", games.size(), games);
        }
        _bayeuxServer.getChannel("/games").publish(_session, games, Promise.noop());
    }

    @Listener("/service/games/challenge")
    public void gameChallenge(ServerSession session, ServerMessage message) {
        Map<String, Object> challenge = message.getDataAsMap();
        String gameId = (String)challenge.get("gameId");
        if ("request".equals(challenge.get("type"))) {
            GameWithOwner gameWithOwner = _newGames.remove(gameId);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("challenge received from {} for game {}", player(session), gameWithOwner);
            }
            if (gameWithOwner != null) {
                _challengedGames.put(gameId, new GameWithOpponent(gameWithOwner, session));
                // Send the challenge to the game owner.
                Map<String, Object> reply = new HashMap<>();
                reply.put("type", "request");
                reply.put("gameId", gameId);
                gameWithOwner.owner.deliver(session, message.getChannel(), reply, Promise.noop());
                broadcastGameList();
            } else {
                // TODO: no game, send back a message to the player
            }
        } else {
            boolean accepted = challenge.get("result") == Boolean.TRUE;
            if (accepted) {
                GameWithOpponent gameWithOpponent = _challengedGames.remove(gameId);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("challenge response from {} for game {}", player(session), gameWithOpponent);
                }
                if (gameWithOpponent != null) {
                    _liveGames.put(gameId, gameWithOpponent);
                    Map<String, Object> reply = new HashMap<>();
                    reply.put("type", "response");
                    reply.put("result", true);
                    Game game = gameWithOpponent.gameWithOwner.game;
                    ServerSession opponent = gameWithOpponent.opponent;
                    game.opponent(player(opponent));
                    reply.put("game", game);
                    gameWithOpponent.gameWithOwner.owner.deliver(opponent, message.getChannel(), reply, Promise.noop());
                    opponent.deliver(session, message.getChannel(), reply, Promise.noop());
                } else {
                    // TODO: challenge accepted but no game, probably the opponent disconnected.
                }
            } else {
                // TODO: challenge not accepted.
            }
        }
    }

    @Listener("/service/games/move")
    public void move(ServerSession session, ServerMessage message) {
        Map<String, Object> move = message.getDataAsMap();
        String gameId = (String)move.get("gameId");
        GameWithOpponent gameWithOpponent = _liveGames.get(gameId);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("received move from {} for game {}: {}", player(session), gameWithOpponent, move);
        }
        if (gameWithOpponent != null) {
            int square = ((Number)move.get("square")).intValue();
            Game game = gameWithOpponent.gameWithOwner.game;
            if (game.move(square)) {
                ServerSession otherSession;
                if (session == gameWithOpponent.opponent) {
                    otherSession = gameWithOpponent.gameWithOwner.owner;
                } else {
                    otherSession = gameWithOpponent.opponent;
                }
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("sending move to {} for game {}: {}", player(otherSession), gameWithOpponent, move);
                }
                otherSession.deliver(session, message.getChannel(), move, Promise.noop());

                if (game.complete()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("game complete {}", game);
                    }
                    Stream.of(session, otherSession).forEach(s -> s.deliver(_session, "/service/games/result", game, Promise.noop()));
                }
            } else {
                // TODO: invalid move.
            }
        }
    }

    private static String player(ServerSession session) {
        return (String)session.getAttribute("player");
    }

    private static class GameWithOwner {
        private final Game game;
        private final ServerSession owner;

        private GameWithOwner(Game game, ServerSession owner) {
            this.game = game;
            this.owner = owner;
        }

        @Override
        public String toString() {
            return game.toString();
        }
    }

    private static class GameWithOpponent {
        private final GameWithOwner gameWithOwner;
        private final ServerSession opponent;

        private GameWithOpponent(GameWithOwner gameWithOwner, ServerSession opponent) {
            this.gameWithOwner = gameWithOwner;
            this.opponent = opponent;
        }

        @Override
        public String toString() {
            return gameWithOwner.toString();
        }
    }
}
