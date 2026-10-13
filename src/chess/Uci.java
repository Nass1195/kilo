package chess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/** Universal Chess Interface front-end, so the bot can be used from GUIs like Arena or Cute Chess. */
public final class Uci {
    private final Searcher searcher = new Searcher();
    private Board board = new Board();
    private Thread searchThread;

    public void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            switch (tokens[0]) {
                case "uci" -> {
                    send("id name " + Main.ENGINE_NAME);
                    send("id author kilo");
                    send("uciok");
                }
                case "isready" -> send("readyok");
                case "ucinewgame" -> {
                    waitForSearch();
                    searcher.clear();
                    board = new Board();
                }
                case "position" -> {
                    waitForSearch();
                    handlePosition(tokens);
                }
                case "go" -> {
                    waitForSearch();
                    handleGo(tokens);
                }
                case "stop" -> {
                    searcher.stop();
                    waitForSearch();
                }
                case "d" -> send(board.toDiagram(true) + "Fen: " + board.toFen());
                case "quit" -> {
                    searcher.stop();
                    waitForSearch();
                    return;
                }
                default -> { }
            }
        }
    }

    private static void send(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private void waitForSearch() {
        if (searchThread != null) {
            try {
                searchThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            searchThread = null;
        }
    }

    private void handlePosition(String[] tokens) {
        int i = 1;
        if (i < tokens.length && tokens[i].equals("startpos")) {
            board = new Board();
            i++;
        } else if (i < tokens.length && tokens[i].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            i++;
            while (i < tokens.length && !tokens[i].equals("moves")) {
                fen.append(tokens[i++]).append(' ');
            }
            board = new Board(fen.toString());
        }
        if (i < tokens.length && tokens[i].equals("moves")) {
            for (i++; i < tokens.length; i++) {
                Move m = board.parseMove(tokens[i]);
                if (m == null) {
                    send("info string illegal move " + tokens[i]);
                    break;
                }
                board.makeMove(m);
            }
        }
    }

    private void handleGo(String[] tokens) {
        int depth = 64;
        long moveTime = 0, wtime = -1, btime = -1, winc = 0, binc = 0;
        int movesToGo = 0;
        boolean infinite = false;
        for (int i = 1; i < tokens.length; i++) {
            String key = tokens[i];
            String value = i + 1 < tokens.length ? tokens[i + 1] : "0";
            switch (key) {
                case "depth" -> depth = Integer.parseInt(value);
                case "movetime" -> moveTime = Long.parseLong(value);
                case "wtime" -> wtime = Long.parseLong(value);
                case "btime" -> btime = Long.parseLong(value);
                case "winc" -> winc = Long.parseLong(value);
                case "binc" -> binc = Long.parseLong(value);
                case "movestogo" -> movesToGo = Integer.parseInt(value);
                case "infinite" -> infinite = true;
                default -> { }
            }
        }

        // The searcher treats the budget as a hard limit and stops starting new
        // iterations after half of it, so a clock budget is twice the target per move.
        long budget = 0;
        if (moveTime > 0) {
            budget = moveTime;
        } else if (!infinite) {
            long remaining = board.sideToMove() == Board.WHITE ? wtime : btime;
            long inc = board.sideToMove() == Board.WHITE ? winc : binc;
            if (remaining >= 0) {
                long target = remaining / (movesToGo > 0 ? movesToGo + 1 : 30) + inc * 3 / 4;
                budget = Math.max(10, Math.min(target * 2, remaining / 3));
            }
        }

        Board searchBoard = board;
        int maxDepth = depth;
        long limit = budget;
        searchThread = new Thread(() -> {
            Searcher.Result r = searcher.search(searchBoard, maxDepth, limit, this::reportInfo);
            send("bestmove " + (r.bestMove() == null ? "0000" : r.bestMove().toUci()));
        });
        searchThread.start();
    }

    private void reportInfo(Searcher.Result r) {
        Integer mate = Searcher.mateIn(r.score());
        String score = mate != null ? "mate " + mate : "cp " + r.score();
        long nps = r.timeMs() > 0 ? r.nodes() * 1000 / r.timeMs() : r.nodes();
        send("info depth " + r.depth() + " score " + score + " nodes " + r.nodes() + " nps " + nps
                + " time " + r.timeMs() + " pv "
                + r.pv().stream().map(Move::toUci).collect(Collectors.joining(" ")));
    }
}
