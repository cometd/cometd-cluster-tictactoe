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
            const newGameButton = $('#new_game');
            newGameButton.on('click', () => {
                model.newGame()
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
                        cometd.subscribe('/service/games/move', message => model.receiveMove(message));
                        cometd.subscribe('/service/games/result', message => model.receiveResult(message));
                    });
                    const player = sessionStorage.getItem('player');
                    if (player) {
                        model.play(player);
                    } else {
                        $('#welcome').show();
                        playerField.focus();
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
                        const gameId = sessionStorage.getItem('gameId');
                        this._debug('resuming game', gameId);
                        if (gameId) {
                            this.getGame(gameId);
                            // TODO: figure out if it's my turn and tell the user
                        } else {
                            this._status('start a new game!');
                        }
                    } else {
                        // TODO: show error.
                    }
                });
            }

            newGame() {
                cometd.remoteCall('/games/new', {}, newGameReply => {
                    if (newGameReply.successful) {
                        const game = newGameReply.data;
                        this._debug('created new game', game);
                        this._setGame(game);
                        this._drawBoard(game);
                    }
                });
            }

            sendGameChallenge(game) {
                this._debug('sending challenge to', game.owner);
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

                const gameList = $('#game-list');
                gameList.empty();

                $.each(games, (i, game) => {
                    // Skip my own game.
                    if (!this._game || this._game.id !== game.id) {
                        const line = $('<div><span>Play against <span class="opponent">' + game.owner + '</span></span></div>');
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

            getGame(gameId) {
                this._debug('getting game', gameId);
                cometd.remoteCall('/games/get', {
                    gameId: gameId
                }, getGameReply => {
                    if (getGameReply.successful) {
                        const game = getGameReply.data;
                        this._debug('got game', game);
                        this._setGame(game);
                        this._drawBoard(game);
                    } else {
                        sessionStorage.removeItem('gameId');
                        this._status('start a new game!');
                    }
                });
            }

            _setGame(game) {
                this._game = game;
                sessionStorage.setItem('gameId', game.id);
            }

            sendMove(index) {
                const game = this._game;
                if (game && game.opponent) {
                    const player = sessionStorage.getItem('player');
                    const myGame = game.owner === player;
                    const myTurn = this._turn(player, game);
                    if (myTurn) {
                        const key = index.toString();
                        if (!game.squares[key]) {
                            const square = $('#sq' + index);
                            const value = myGame ? 'X' : 'O';
                            game.squares[key] = value;
                            square.text(value);
                            this._debug('send move', value, 'in', index);
                            cometd.publish('/service/games/move', {
                                gameId: game.id,
                                square: index
                            });
                        }
                    }
                }
            }

            receiveMove(message) {
                const game = this._game;
                const index = message.data.square;
                const value = Object.keys(game.squares).length % 2 === 0 ? 'X' : 'O';
                this._debug('received move', index, '=', value);
                const key = index.toString();
                game.squares[key] = value;
                const square = $('#sq' + index);
                square.text(value);
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
                const moves = Object.keys(game.squares).length;
                if (myGame && moves % 2 === 0) {
                    return true;
                }
                return !myGame && moves % 2 !== 0;
            }

            _debug() {
                // TODO: change to debug().
                console.info(...arguments);
            }
        }
    }
);
