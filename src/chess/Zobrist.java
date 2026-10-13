package chess;

import java.util.Random;

/** Random keys used to hash positions for the transposition table and repetition detection. */
final class Zobrist {
    static final long[][] PIECES = new long[12][64];
    static final long[] CASTLING = new long[16];
    static final long[] EP_FILE = new long[8];
    static final long SIDE;

    static {
        Random rng = new Random(0x5EEDC0DEL);
        for (long[] table : PIECES) {
            for (int sq = 0; sq < 64; sq++) {
                table[sq] = rng.nextLong();
            }
        }
        for (int i = 0; i < CASTLING.length; i++) {
            CASTLING[i] = rng.nextLong();
        }
        for (int i = 0; i < EP_FILE.length; i++) {
            EP_FILE[i] = rng.nextLong();
        }
        SIDE = rng.nextLong();
    }

    private Zobrist() {
    }

    /** Key for a signed piece code (positive = white, negative = black) on a square. */
    static long piece(int piece, int sq) {
        int index = piece > 0 ? piece - 1 : 5 - piece;
        return PIECES[index][sq];
    }
}
