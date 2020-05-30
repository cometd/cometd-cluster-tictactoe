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

package org.cometd.demo.cluster.tictactoe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.ajax.JSON;

public class Game implements JSON.Convertible {
    private String id;
    private String owner;
    private String opponent;
    private transient String winner;
    private final List<Move> moves = new ArrayList<>();

    public Game() {
    }

    public Game(String id, String owner) {
        this.id = id;
        this.owner = owner;
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public String opponent() {
        return opponent;
    }

    public void opponent(String opponent) {
        this.opponent = opponent;
    }

    public boolean move(Move move) {
        boolean valid = moves.stream()
                .filter(m -> m.square == move.square)
                .findAny()
                .orElse(null) == null;

        if (valid) {
            moves.add(move);
            return true;
        }

        return false;
    }

    public boolean complete() {
        String winner = result();
        if (winner == null) {
            return moves.size() == 9;
        } else {
            this.winner = winner;
            return true;
        }
    }

    private String result() {
        Map<Integer, Integer> board = moves.stream()
                .collect(Collectors.toMap(move -> move.square, move -> move.sequence % 2));
        if (strike(board, 0, 1, 2) || strike(board, 0, 3, 6) || strike(board, 0, 4, 8)) {
            return board.get(0) == 0 ? owner : opponent;
        }
        if (strike(board, 3, 4, 5) || strike(board, 1, 4, 7) || strike(board, 2, 4, 6)) {
            return board.get(4) == 0 ? owner : opponent;
        }
        if (strike(board, 6, 7, 8) || strike(board, 2, 5, 8)) {
            return board.get(8) == 0 ? owner : opponent;
        }
        return null;
    }

    private boolean strike(Map<Integer, Integer> board, int square1, int square2, int square3) {
        Integer value1 = board.get(square1);
        if (value1 != null) {
            Integer value2 = board.get(square2);
            if (value2 != null) {
                Integer value3 = board.get(square3);
                if (value3 != null) {
                    return Objects.equals(value1, value2) && Objects.equals(value2, value3);
                }
            }
        }
        return false;
    }

    @Override
    public void toJSON(JSON.Output out) {
        out.addClass(Game.class);
        out.add("id", id);
        out.add("owner", owner);
        out.add("opponent", opponent);
        out.add("winner", winner);
        out.add("moves", moves);
    }

    @Override
    public void fromJSON(Map object) {
        this.id = (String)object.get("id");
        this.owner = (String)object.get("owner");
        this.opponent = (String)object.get("opponent");
        this.winner = (String)object.get("winner");
        Object jsonMoves = object.get("moves");
        if (jsonMoves instanceof List) {
            @SuppressWarnings("unchecked")
            List<Move> moves = (List<Move>)jsonMoves;
            this.moves.addAll(moves);
        } else if (jsonMoves instanceof Object[]) {
            Object[] moves = (Object[])jsonMoves;
            for (Object move : moves) {
                this.moves.add((Move)move);
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public String toString() {
        return String.format("%s@%x[<#%s>%s|%s=>%s]%s", getClass().getSimpleName(), hashCode(), id, owner, opponent, winner, moves);
    }

    public static class Move implements JSON.Convertible {
        public String gameId; // unique across cluster
        public int square; // which square
        private int sequence; // within the game

        @Override
        public void toJSON(JSON.Output out) {
            out.addClass(Move.class);
            out.add("gameId", gameId);
            out.add("square", square);
            out.add("sequence", sequence);
        }

        @Override
        public void fromJSON(Map object) {
            this.gameId = (String)object.get("gameId");
            this.square = ((Number)object.get("square")).intValue();
            this.sequence = ((Number)object.get("sequence")).intValue();
        }

        @Override
        public String toString() {
            return String.format("#%s[%d=%s]", gameId, square, sequence % 2 == 0 ? "X" : "O");
        }
    }
}
