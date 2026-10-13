package chess;

/**
 * Static evaluation: material, piece-square tables (tapered for the king), bishop pair,
 * and a few pawn-structure terms. Scores are in centipawns from the side to move's view.
 */
public final class Evaluator {
    static final int[] PIECE_VALUES = {0, 100, 320, 330, 500, 900, 20000};

    // Tables are written from White's point of view with rank 8 on the first row.
    private static final int[] PAWN_PST = {
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] KNIGHT_PST = {
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50};
    private static final int[] BISHOP_PST = {
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20};
    private static final int[] ROOK_PST = {
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0};
    private static final int[] QUEEN_PST = {
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20};
    private static final int[] KING_MG_PST = {
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20};
    private static final int[] KING_EG_PST = {
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50};

    private static final int[][] PST = {null, PAWN_PST, KNIGHT_PST, BISHOP_PST, ROOK_PST, QUEEN_PST};
    private static final int[] PHASE_WEIGHT = {0, 0, 1, 1, 2, 4, 0};
    private static final int MAX_PHASE = 24;

    private static final int BISHOP_PAIR = 30;
    private static final int DOUBLED_PAWN = -15;
    private static final int ISOLATED_PAWN = -12;
    private static final int[] PASSED_PAWN = {0, 5, 10, 20, 35, 60, 100, 0};

    private Evaluator() {
    }

    public static int evaluate(Board board) {
        int[] sq = board.squares;
        int score = 0;
        int phase = 0;
        int whiteBishops = 0, blackBishops = 0;
        int whiteKing = -1, blackKing = -1;
        int[] whitePawnFiles = new int[8], blackPawnFiles = new int[8];

        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == 0) {
                continue;
            }
            int type = Math.abs(p);
            phase += PHASE_WEIGHT[type];
            if (p > 0) {
                if (type == Board.KING) {
                    whiteKing = s;
                    continue;
                }
                score += PIECE_VALUES[type] + PST[type][s ^ 56];
                if (type == Board.BISHOP) whiteBishops++;
                if (type == Board.PAWN) whitePawnFiles[s & 7]++;
            } else {
                if (type == Board.KING) {
                    blackKing = s;
                    continue;
                }
                score -= PIECE_VALUES[type] + PST[type][s];
                if (type == Board.BISHOP) blackBishops++;
                if (type == Board.PAWN) blackPawnFiles[s & 7]++;
            }
        }

        phase = Math.min(phase, MAX_PHASE);
        if (whiteKing >= 0) {
            score += taper(KING_MG_PST[whiteKing ^ 56], KING_EG_PST[whiteKing ^ 56], phase);
        }
        if (blackKing >= 0) {
            score -= taper(KING_MG_PST[blackKing], KING_EG_PST[blackKing], phase);
        }

        if (whiteBishops >= 2) score += BISHOP_PAIR;
        if (blackBishops >= 2) score -= BISHOP_PAIR;

        score += pawnStructure(sq, whitePawnFiles, blackPawnFiles, phase);

        return board.side == Board.WHITE ? score : -score;
    }

    private static int taper(int mg, int eg, int phase) {
        return (mg * phase + eg * (MAX_PHASE - phase)) / MAX_PHASE;
    }

    private static int pawnStructure(int[] sq, int[] whiteFiles, int[] blackFiles, int phase) {
        int score = 0;
        for (int f = 0; f < 8; f++) {
            if (whiteFiles[f] > 1) score += DOUBLED_PAWN * (whiteFiles[f] - 1);
            if (blackFiles[f] > 1) score -= DOUBLED_PAWN * (blackFiles[f] - 1);
            boolean whiteNeighbours = (f > 0 && whiteFiles[f - 1] > 0) || (f < 7 && whiteFiles[f + 1] > 0);
            boolean blackNeighbours = (f > 0 && blackFiles[f - 1] > 0) || (f < 7 && blackFiles[f + 1] > 0);
            if (whiteFiles[f] > 0 && !whiteNeighbours) score += ISOLATED_PAWN * whiteFiles[f];
            if (blackFiles[f] > 0 && !blackNeighbours) score -= ISOLATED_PAWN * blackFiles[f];
        }

        // Passed pawns grow more valuable as material comes off the board.
        for (int s = 0; s < 64; s++) {
            int p = sq[s];
            if (p == Board.PAWN && isPassed(sq, s, Board.WHITE)) {
                score += taper(PASSED_PAWN[s >> 3] / 2, PASSED_PAWN[s >> 3], phase);
            } else if (p == -Board.PAWN && isPassed(sq, s, Board.BLACK)) {
                score -= taper(PASSED_PAWN[7 - (s >> 3)] / 2, PASSED_PAWN[7 - (s >> 3)], phase);
            }
        }
        return score;
    }

    private static boolean isPassed(int[] sq, int s, int color) {
        int f = s & 7;
        int enemyPawn = -Board.PAWN * color;
        for (int r = (s >> 3) + color; r >= 0 && r < 8; r += color) {
            for (int df = -1; df <= 1; df++) {
                int tf = f + df;
                if (tf >= 0 && tf < 8 && sq[r * 8 + tf] == enemyPawn) {
                    return false;
                }
            }
        }
        return true;
    }
}
