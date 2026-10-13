package chess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Entry point. Without arguments it starts an interactive game in the terminal;
 * with the argument {@code uci} it speaks the UCI protocol for chess GUIs.
 */
public final class Main {
    static final String ENGINE_NAME = "KiloChess 1.0";

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    private final Searcher searcher = new Searcher();
    private Board board = new Board();
    private int humanColor = Board.WHITE;
    private long thinkTimeMs = 3000;

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equalsIgnoreCase("uci")) {
            new Uci().run();
        } else {
            new Main().play();
        }
    }

    private void play() throws IOException {
        System.out.println(ENGINE_NAME + " - type 'help' for commands.");
        String color = prompt("Play as (w)hite, (b)lack, or (n)one to watch the bot play itself? [w] ");
        if (color == null) {
            return;
        }
        humanColor = switch (color.trim().toLowerCase()) {
            case "b", "black" -> Board.BLACK;
            case "n", "none" -> 0;
            default -> Board.WHITE;
        };

        while (true) {
            System.out.println();
            System.out.print(board.toDiagram(humanColor != Board.BLACK));
            String result = board.gameResult();
            if (result != null) {
                System.out.println("Game over: " + result);
                String again = prompt("Type 'new' to play again, or anything else to quit: ");
                if (again != null && again.trim().equalsIgnoreCase("new")) {
                    board = new Board();
                    searcher.clear();
                    continue;
                }
                return;
            }
            if (board.inCheck()) {
                System.out.println("Check!");
            }

            if (board.sideToMove() == humanColor) {
                if (!humanTurn()) {
                    return;
                }
            } else {
                botTurn();
            }
        }
    }

    /** Handles one human command. Returns false when the user quits. */
    private boolean humanTurn() throws IOException {
        while (true) {
            String line = prompt(sideName(board.sideToMove()) + " to move> ");
            if (line == null) {
                return false;
            }
            String[] parts = line.trim().split("\\s+");
            switch (parts[0].toLowerCase()) {
                case "" -> {
                    continue;
                }
                case "help" -> printHelp();
                case "quit", "exit" -> {
                    return false;
                }
                case "moves" -> System.out.println(board.generateLegalMoves().stream()
                        .map(board::toSan).sorted().collect(Collectors.joining(" ")));
                case "fen" -> System.out.println(board.toFen());
                case "undo" -> {
                    // Take back the bot's reply and the human's move.
                    int plies = Math.min(2, board.ply());
                    for (int i = 0; i < plies; i++) {
                        board.unmakeMove();
                    }
                    return true;
                }
                case "hint" -> {
                    Searcher.Result r = searcher.search(board, 64, Math.min(thinkTimeMs, 2000), null);
                    System.out.println("Hint: " + board.toSan(r.bestMove()));
                }
                case "time" -> {
                    if (parts.length > 1) {
                        thinkTimeMs = (long) (Double.parseDouble(parts[1]) * 1000);
                    }
                    System.out.println("Bot thinks for " + thinkTimeMs / 1000.0 + "s per move.");
                }
                case "flip" -> {
                    humanColor = -board.sideToMove();
                    return true;
                }
                case "new" -> {
                    board = new Board();
                    searcher.clear();
                    return true;
                }
                case "setfen" -> {
                    try {
                        board = new Board(line.trim().substring(6).trim());
                        searcher.clear();
                        return true;
                    } catch (RuntimeException e) {
                        System.out.println("Invalid FEN: " + e.getMessage());
                    }
                }
                default -> {
                    Move m = board.parseMove(parts[0]);
                    if (m == null) {
                        System.out.println("Illegal or unrecognised move: " + parts[0]
                                + " (use e.g. e2e4 or Nf3; type 'moves' to list legal moves)");
                        continue;
                    }
                    System.out.println("You play " + board.toSan(m));
                    board.makeMove(m);
                    return true;
                }
            }
        }
    }

    private void botTurn() {
        System.out.println(ENGINE_NAME + " is thinking...");
        Searcher.Result r = searcher.search(board, 64, thinkTimeMs, null);
        Move m = r.bestMove();
        Integer mate = Searcher.mateIn(r.score());
        String eval = mate != null ? "mate in " + mate : String.format("%+.2f", r.score() / 100.0);
        List<String> pv = sanLine(r.pv());
        System.out.printf("%s plays %s   (depth %d, eval %s, %d nodes, %.1fs)%n",
                ENGINE_NAME, board.toSan(m), r.depth(), eval, r.nodes(), r.timeMs() / 1000.0);
        if (pv.size() > 1) {
            System.out.println("Expected line: " + String.join(" ", pv));
        }
        board.makeMove(m);
    }

    private List<String> sanLine(List<Move> pv) {
        List<String> san = new java.util.ArrayList<>();
        for (Move m : pv) {
            san.add(board.toSan(m));
            board.makeMove(m);
        }
        for (int i = 0; i < pv.size(); i++) {
            board.unmakeMove();
        }
        return san;
    }

    private String prompt(String text) throws IOException {
        System.out.print(text);
        System.out.flush();
        return in.readLine();
    }

    private static String sideName(int color) {
        return color == Board.WHITE ? "White" : "Black";
    }

    private static void printHelp() {
        System.out.println("""
                Enter moves in coordinate (e2e4, e7e8q) or algebraic (e4, Nf3, O-O, exd5) notation.
                Commands:
                  moves          list legal moves
                  undo           take back your last move
                  hint           ask the bot for a suggestion
                  time <secs>    set the bot's thinking time per move
                  flip           swap sides with the bot
                  fen            print the current position as FEN
                  setfen <fen>   load a position
                  new            start a new game
                  quit           exit""");
    }
}
