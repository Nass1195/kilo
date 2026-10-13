package chess;

/**
 * Verifies move generation by counting leaf nodes ("perft") against well-known reference values,
 * then checks that the bot finds a few forced tactics.
 */
public final class PerftTest {
    private static int failures = 0;

    public static void main(String[] args) {
        perft(Board.START_FEN, 1, 20);
        perft(Board.START_FEN, 2, 400);
        perft(Board.START_FEN, 3, 8_902);
        perft(Board.START_FEN, 4, 197_281);
        // "Kiwipete": castling, en passant, promotions and pins all at once.
        perft("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 3, 97_862);
        perft("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 5, 674_624);
        perft("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 3, 9_467);
        perft("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 3, 62_379);

        // Mate in one: Qxf7#
        bestMove("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 0 1", "f3f7");
        // Back-rank mate in one: Re8#
        bestMove("6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1", "e1e8");
        // Win the undefended queen.
        bestMove("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1", "d1d5");

        if (failures > 0) {
            System.out.println(failures + " test(s) FAILED");
            System.exit(1);
        }
        System.out.println("All tests passed");
    }

    private static void perft(String fen, int depth, long expected) {
        Board board = new Board(fen);
        String before = board.toFen();
        long start = System.currentTimeMillis();
        long nodes = count(board, depth);
        long ms = System.currentTimeMillis() - start;
        boolean ok = nodes == expected && board.toFen().equals(before);
        if (!ok) failures++;
        System.out.printf("%s perft(%d) = %,d (expected %,d) in %d ms  [%s]%n",
                ok ? "PASS" : "FAIL", depth, nodes, expected, ms, fen);
    }

    private static long count(Board board, int depth) {
        if (depth == 0) return 1;
        long total = 0;
        for (Move m : board.generateLegalMoves()) {
            long hashBefore = board.hash();
            board.makeMove(m);
            total += count(board, depth - 1);
            board.unmakeMove();
            if (board.hash() != hashBefore) {
                throw new IllegalStateException("Hash not restored after " + m);
            }
        }
        return total;
    }

    private static void bestMove(String fen, String expected) {
        Board board = new Board(fen);
        Searcher.Result r = new Searcher(16).search(board, 6, 5000, null);
        boolean ok = r.bestMove() != null && r.bestMove().toUci().equals(expected);
        if (!ok) failures++;
        System.out.printf("%s best move %s (expected %s, depth %d, score %d)  [%s]%n",
                ok ? "PASS" : "FAIL", r.bestMove(), expected, r.depth(), r.score(), fen);
    }
}
