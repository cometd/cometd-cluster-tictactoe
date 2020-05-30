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

require({
        baseUrl: 'js/jquery',
        paths: {
            jquery: 'https://code.jquery.com/jquery-3.4.1',
            cometd: '../cometd'
        }
    },
    ['jquery', 'jquery.cometd', 'jquery.cometd-reload'],
    function($, cometd) {
        $(document).ready(function() {
            const model = new TicTacToe();

            // Setup UI.
            const playerField = $('#name');
            $('#play').on('click', () => {
                const player = playerField.val();
                if (player && player.trim()) {
                    model.play(player);
                }
            });
            const newGameButton = $('#newGame');
            newGameButton.on('click', () => {
                model.newGame()
            });
            const migrateButton = $('#migrate');
            migrateButton.on('click', () => {
                model.migrate();
            });
            for (let i = 0; i < 9; ++i) {
                $('#sq' + i).on('click', () => {
                    model.sendMove(i);
                });
            }
            $('#welcome').hide();
            $('#main').hide();

            // Initialize CometD.
            // Setup the reload extension.
            $(window).on('beforeunload', cometd.reload);

            const path = location.pathname;
            const contextPath = path.substring(0, path.lastIndexOf('/'));
            const cometURL = location.protocol + '//' + location.host + contextPath + '/cometd';
            cometd.configure({
                url: cometURL,
                logLevel: 'info'
            });

            cometd.handshake(message => {
                if (message.successful) {
                    model._debug('handshake successful');
                    cometd.batch(() => {
                        cometd.subscribe('/games', message => model.receiveGameList(message));
                        cometd.subscribe('/service/games/challenge', message => model.receiveGameChallenge(message));
                        cometd.subscribe('/games/move', message => model.receiveMove(message));
                        cometd.subscribe('/games/result', message => model.receiveResult(message));
                        cometd.subscribe('/service/games/migrate', message => model.receiveMigrate(message));
                    });
                    const player = sessionStorage.getItem('player');
                    if (player) {
                        model.play(player);
                    } else {
                        const params = new URLSearchParams(location.search);
                        const player = params.get('player');
                        if (player) {
                            model.play(player);
                        } else {
                            $('#welcome').show();
                            playerField.focus();
                        }
                    }
                }
            });
        });

        class TicTacToe {
            play(player) {
                cometd.remoteCall('/games/play', {
                    player: player
                }, playReply => {
                    if (playReply.successful) {
                        const player = playReply.data;
                        sessionStorage.setItem('player', player);
                        $('#welcome').hide();
                        $('#main').show();
                        this._debug('resuming game for', player);
                        this.findGame(player, game => {
                            if (!game) {
                                this._status('start a new game!');
                            }
                        });
                    } else {
                        // TODO: show error.
                    }
                });
            }

            newGame() {
                $('#newGame').prop('disabled', true);
                cometd.remoteCall('/games/new', {}, newGameReply => {
                    if (newGameReply.successful) {
                        const game = newGameReply.data;
                        this._debug('created new game', game);
                        this._setGame(game);
                        this._drawBoard(game);
                    }
                });
            }

            _setGame(game) {
                this._game = game;
                game.squares = this._squares(game);
            }

            sendGameChallenge(game) {
                this._debug('sending challenge to', game.owner);
                $('#newGame').prop('disabled', true);
                cometd.publish('/service/games/challenge', {
                    gameId: game.id,
                    type: 'request'
                });
            }

            receiveGameChallenge(message) {
                const challenge = message.data;
                if (challenge.type === 'request') {
                    this._debug('challenge request', challenge);
                    // TODO: present dialog to player to accept the challenge.

                    // Accept the challenge.
                    cometd.publish(message.channel, {
                        gameId: challenge.gameId,
                        type: 'response',
                        result: true
                    });
                } else if (challenge.type === 'response') {
                    if (challenge.result === true) {
                        this._debug('challenge accepted', challenge);
                        const game = challenge.game;
                        this._setGame(game);
                        this._drawBoard(game);
                    } else {
                        this._debug('challenge not accepted', challenge);
                    }
                } else {
                    this._debug('unexpected challenge', challenge);
                }
            }

            receiveGameList(message) {
                const games = message.data;
                this._debug('received games list', games);

                const gameList = $('#gameList');
                gameList.empty();

                const player = sessionStorage.getItem('player');
                $.each(games, (i, game) => {
                    // Skip my own game.
                    const owner = game.owner;
                    if (player !== owner) {
                        const line = $('<div><span>Play against <span class="opponent">' + owner + '</span></span></div>');
                        line.on('click', () => this.sendGameChallenge(game));
                        gameList.append(line);
                    }
                });
            }

            _status(text) {
                const status = $('#status');
                status.empty();
                status.append($('<span>' + text + '</span>'));
            }

            findGame(player, callback) {
                this._debug('finding game for', player);
                cometd.remoteCall('/games/find', {
                    player: player
                }, getGameReply => {
                    if (getGameReply.successful) {
                        const game = getGameReply.data;
                        this._debug('found game', game);
                        this._setGame(game);
                        this._drawBoard(game);
                        callback(game);
                    } else {
                        this._debug('no game found');
                        this._status('start a new game!');
                        callback();
                    }
                });
            }

            sendMove(index) {
                const game = this._game;
                if (game && game.opponent) {
                    const player = sessionStorage.getItem('player');
                    const myTurn = this._turn(player, game);
                    if (myTurn) {
                        const key = index.toString();
                        if (!game.squares[key]) {
                            // The square is empty.
                            const sequence = game.moves.length;
                            const move = {
                                gameId: game.id,
                                square: index,
                                sequence: sequence
                            };
                            this._debug('send move', move);
                            cometd.publish('/service/games/move', move);
                        }
                    }
                }
            }

            receiveMove(message) {
                const game = this._game;
                const move = message.data;
                const index = move.square;
                const value = game.moves.length % 2 === 0 ? 'X' : 'O';
                this._debug('received move', index, '=', value);
                game.moves.push(move);
                const key = index.toString();
                game.squares[key] = value;
                this._drawBoard(game);
            }

            receiveResult(message) {
                const game = message.data;
                const winner = game.winner;
                this._debug('received result:', winner ? winner + ' wins' : 'draw');
                if (winner) {
                    const player = sessionStorage.getItem('player');
                    if (player === winner) {
                        this._status('you won!');
                    } else {
                        this._status('you lost!');
                    }
                } else {
                    this._status('the game is a draw');
                }
                // TODO: draw the strike across the squares.
                $('#newGame').prop('disabled', false);
            }

            migrate() {
                // Migrate after a random time between 2 and 12 seconds.
                setTimeout(() => {
                    cometd.publish('/service/games/migrate', {});
                }, (Math.floor(Math.random() * 10) + 2) * 1000);
            }

            receiveMigrate(message) {
                const url = message.data;
                this._debug('Moving to', url)
                location = url;
            }

            _drawBoard(game) {
                const player = sessionStorage.getItem('player');
                const playerUI = $('#player');
                playerUI.empty().append('<span>' + player + '</span>');

                const myGame = game.owner === player;
                let opponent = game.opponent || '&nbsp;';
                if (!myGame) {
                    opponent = game.owner || '&nbsp;';
                }
                const opponentUI = $('#opponent');
                opponentUI.empty().append('<span>' + opponent + '</span>');

                for (let i = 0; i < 9; ++i) {
                    const square = game.squares[i.toString()];
                    const text = square ? square : '&nbsp;';
                    $('#sq' + i).html(text);
                }

                playerUI.toggleClass('turn', this._turn(player, game));
                opponentUI.toggleClass('turn', this._turn(opponent, game));

                if (game.opponent) {
                    this._status('&nbsp;');
                } else {
                    this._status('game #' + game.id + ' - waiting for opponents...');
                }
            }

            _turn(player, game) {
                const myGame = game.owner === player;
                const moves = game.moves.length;
                if (myGame && moves % 2 === 0) {
                    return true;
                }
                return !myGame && moves % 2 !== 0;
            }

            _squares(game) {
                const result = {};
                for (const move of game.moves) {
                    result[move.square.toString()] = move.sequence % 2 === 0 ? 'X' : 'O';
                }
                return result;
            }

            _debug() {
                // TODO: change to debug().
                console.info(...arguments);
            }
        }
    }
);
