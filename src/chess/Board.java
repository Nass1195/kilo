package chess;

import java.util.ArrayList;
import java.util.List;

/**
 * Mailbox board representation with full legal move generation (castling, en passant,
 * promotion), make/unmake, FEN support and draw detection.
 *
 * Pieces are signed ints: positive for white, negative for black, 0 for empty.
 */
public final class Board {
    public static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    public static final int WHITE = 1, BLACK = -1;
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final String PIECE_CHARS = ".PNBRQK";

    static final int WK_CASTLE = 1, WQ_CASTLE = 2, BK_CASTLE = 4, BQ_CASTLE = 8;

    private static final int[][] KNIGHT_DIRS = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    private static final int[][] KING_DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] ROOK_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Castling rights that survive a move touching each square. */
    private static final int[] CASTLE_MASK = new int[64];

    static {
        java.util.Arrays.fill(CASTLE_MASK, 15);
        CASTLE_MASK[0] &= ~WQ_CASTLE;
        CASTLE_MASK[7] &= ~WK_CASTLE;
        CASTLE_MASK[4] &= ~(WK_CASTLE | WQ_CASTLE);
        CASTLE_MASK[56] &= ~BQ_CASTLE;
        CASTLE_MASK[63] &= ~BK_CASTLE;
        CASTLE_MASK[60] &= ~(BK_CASTLE | BQ_CASTLE);
    }

    private static final class Undo {
        final Move move;
        final int captured;
        final int castling;
        final int epSquare;
        final int halfmoveClock;
        final long hash;

        Undo(Move move, int captured, int castling, int epSquare, int halfmoveClock, long hash) {
            this.move = move;
            this.captured = captured;
            this.castling = castling;
            this.epSquare = epSquare;
            this.halfmoveClock = halfmoveClock;
            this.hash = hash;
        }
    }

    final int[] squares = new int[64];
    int side;
    int castling;
    int epSquare = -1;
    int halfmoveClock;
    int fullmoveNumber = 1;
    long hash;

    private final int[] kingSquare = new int[2];
    private final ArrayList<Undo> history = new ArrayList<>();

    public Board() {
        setFen(START_FEN);
    }

    public Board(String fen) {
        setFen(fen);
    }

    // ------------------------------------------------------------------ helpers

    public static String squareName(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 3));
    }

    public static int parseSquare(String s) {
        int file = s.charAt(0) - 'a';
        int rank = s.charAt(1) - '1';
        if (file < 0 || file > 7 || rank < 0 || rank > 7) {
            throw new IllegalArgumentException("Bad square: " + s);
        }
        return rank * 8 + file;
    }

    private static int colorIndex(int color) {
        return color == WHITE ? 0 : 1;
    }

    public int sideToMove() {
        return side;
    }

    public int pieceAt(int sq) {
        return squares[sq];
    }

    public long hash() {
        return hash;
    }

    public int ply() {
        return history.size();
    }

    // ------------------------------------------------------------------ FEN

    public void setFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Bad FEN: " + fen);
        }
        java.util.Arrays.fill(squares, 0);
        history.clear();
        int rank = 7, file = 0;
        for (char c : parts[0].toCharArray()) {
            if (c == '/') {
                rank--;
                file = 0;
            } else if (Character.isDigit(c)) {
                file += c - '0';
            } else {
                int type = PIECE_CHARS.indexOf(Character.toUpperCase(c));
                if (type <= 0 || rank < 0 || file > 7) {
                    throw new IllegalArgumentException("Bad FEN: " + fen);
                }
                int piece = Character.isUpperCase(c) ? type : -type;
                int sq = rank * 8 + file;
                squares[sq] = piece;
                if (type == KING) {
                    kingSquare[colorIndex(piece > 0 ? WHITE : BLACK)] = sq;
                }
                file++;
            }
        }
        side = parts[1].equals("b") ? BLACK : WHITE;
        castling = 0;
        if (parts.length > 2) {
            for (char c : parts[2].toCharArray()) {
                switch (c) {
                    case 'K' -> castling |= WK_CASTLE;
                    case 'Q' -> castling |= WQ_CASTLE;
                    case 'k' -> castling |= BK_CASTLE;
                    case 'q' -> castling |= BQ_CASTLE;
                    default -> { }
                }
            }
        }
        epSquare = parts.length > 3 && !parts[3].equals("-") ? parseSquare(parts[3]) : -1;
        halfmoveClock = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        fullmoveNumber = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        hash = computeHash();
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int p = squares[rank * 8 + file];
                if (p == 0) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                char c = PIECE_CHARS.charAt(Math.abs(p));
                sb.append(p > 0 ? c : Character.toLowerCase(c));
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        sb.append(side == WHITE ? " w " : " b ");
        String rights = ((castling & WK_CASTLE) != 0 ? "K" : "") + ((castling & WQ_CASTLE) != 0 ? "Q" : "")
                + ((castling & BK_CASTLE) != 0 ? "k" : "") + ((castling & BQ_CASTLE) != 0 ? "q" : "");
        sb.append(rights.isEmpty() ? "-" : rights);
        sb.append(' ').append(epSquare >= 0 ? squareName(epSquare) : "-");
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber);
        return sb.toString();
    }

    private long computeHash() {
        long h = 0;
        for (int sq = 0; sq < 64; sq++) {
            if (squares[sq] != 0) {
                h ^= Zobrist.piece(squares[sq], sq);
            }
        }
        h ^= Zobrist.CASTLING[castling];
        if (epSquare >= 0) {
            h ^= Zobrist.EP_FILE[epSquare & 7];
        }
        if (side == BLACK) {
            h ^= Zobrist.SIDE;
        }
        return h;
    }

    // ------------------------------------------------------------------ make / unmake

    public void makeMove(Move m) {
        int piece = squares[m.from];
        int capSq = (m.flags & Move.EN_PASSANT) != 0 ? m.to - 8 * side : m.to;
        int captured = squares[capSq];
        history.add(new Undo(m, captured, castling, epSquare, halfmoveClock, hash));

        if (epSquare >= 0) {
            hash ^= Zobrist.EP_FILE[epSquare & 7];
        }
        hash ^= Zobrist.CASTLING[castling];

        if (captured != 0) {
            hash ^= Zobrist.piece(captured, capSq);
            squares[capSq] = 0;
        }
        hash ^= Zobrist.piece(piece, m.from);
        squares[m.from] = 0;
        int placed = m.promotion != 0 ? m.promotion * side : piece;
        squares[m.to] = placed;
        hash ^= Zobrist.piece(placed, m.to);

        if (Math.abs(piece) == KING) {
            kingSquare[colorIndex(side)] = m.to;
            if ((m.flags & Move.CASTLE) != 0) {
                int rookFrom = m.to > m.from ? m.from + 3 : m.from - 4;
                int rookTo = m.to > m.from ? m.from + 1 : m.from - 1;
                int rook = squares[rookFrom];
                squares[rookFrom] = 0;
                squares[rookTo] = rook;
                hash ^= Zobrist.piece(rook, rookFrom) ^ Zobrist.piece(rook, rookTo);
            }
        }

        castling &= CASTLE_MASK[m.from] & CASTLE_MASK[m.to];
        hash ^= Zobrist.CASTLING[castling];

        epSquare = -1;
        if ((m.flags & Move.DOUBLE_PUSH) != 0) {
            epSquare = (m.from + m.to) / 2;
            hash ^= Zobrist.EP_FILE[epSquare & 7];
        }

        halfmoveClock = (Math.abs(piece) == PAWN || captured != 0) ? 0 : halfmoveClock + 1;
        if (side == BLACK) {
            fullmoveNumber++;
        }
        side = -side;
        hash ^= Zobrist.SIDE;
    }

    public void unmakeMove() {
        Undo u = history.remove(history.size() - 1);
        Move m = u.move;
        side = -side;
        if (side == BLACK) {
            fullmoveNumber--;
        }
        int placed = squares[m.to];
        int piece = m.promotion != 0 ? PAWN * side : placed;
        squares[m.to] = 0;
        squares[m.from] = piece;
        int capSq = (m.flags & Move.EN_PASSANT) != 0 ? m.to - 8 * side : m.to;
        squares[capSq] = u.captured;

        if (Math.abs(piece) == KING) {
            kingSquare[colorIndex(side)] = m.from;
            if ((m.flags & Move.CASTLE) != 0) {
                int rookFrom = m.to > m.from ? m.from + 3 : m.from - 4;
                int rookTo = m.to > m.from ? m.from + 1 : m.from - 1;
                squares[rookFrom] = squares[rookTo];
                squares[rookTo] = 0;
            }
        }
        castling = u.castling;
        epSquare = u.epSquare;
        halfmoveClock = u.halfmoveClock;
        hash = u.hash;
    }

    /** Passes the turn without moving (used by null-move pruning). */
    void makeNullMove() {
        history.add(new Undo(null, 0, castling, epSquare, halfmoveClock, hash));
        if (epSquare >= 0) {
            hash ^= Zobrist.EP_FILE[epSquare & 7];
        }
        epSquare = -1;
        halfmoveClock++;
        side = -side;
        hash ^= Zobrist.SIDE;
    }

    void unmakeNullMove() {
        Undo u = history.remove(history.size() - 1);
        side = -side;
        epSquare = u.epSquare;
        halfmoveClock = u.halfmoveClock;
        hash = u.hash;
    }

    // ------------------------------------------------------------------ attacks

    private int at(int file, int rank) {
        return squares[rank * 8 + file];
    }

    private static boolean onBoard(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    /** True if {@code sq} is attacked by any piece of color {@code by}. */
    public boolean isSquareAttacked(int sq, int by) {
        int f = sq & 7, r = sq >> 3;

        int pr = r - by;
        if (pr >= 0 && pr < 8) {
            if (f > 0 && at(f - 1, pr) == PAWN * by) return true;
            if (f < 7 && at(f + 1, pr) == PAWN * by) return true;
        }
        for (int[] d : KNIGHT_DIRS) {
            int tf = f + d[0], tr = r + d[1];
            if (onBoard(tf, tr) && at(tf, tr) == KNIGHT * by) return true;
        }
        for (int[] d : KING_DIRS) {
            int tf = f + d[0], tr = r + d[1];
            if (onBoard(tf, tr) && at(tf, tr) == KING * by) return true;
        }
        if (slideAttack(f, r, BISHOP_DIRS, BISHOP * by, QUEEN * by)) return true;
        return slideAttack(f, r, ROOK_DIRS, ROOK * by, QUEEN * by);
    }

    private boolean slideAttack(int f, int r, int[][] dirs, int slider, int queen) {
        for (int[] d : dirs) {
            int tf = f + d[0], tr = r + d[1];
            while (onBoard(tf, tr)) {
                int p = at(tf, tr);
                if (p != 0) {
                    if (p == slider || p == queen) return true;
                    break;
                }
                tf += d[0];
                tr += d[1];
            }
        }
        return false;
    }

    public boolean inCheck() {
        return isSquareAttacked(kingSquare[colorIndex(side)], -side);
    }

    /** After makeMove: true if the side that just moved left its own king in check. */
    boolean leftKingInCheck() {
        return isSquareAttacked(kingSquare[colorIndex(-side)], side);
    }

    // ------------------------------------------------------------------ move generation

    public List<Move> generateLegalMoves() {
        List<Move> pseudo = generatePseudoLegalMoves(false);
        List<Move> legal = new ArrayList<>(pseudo.size());
        for (Move m : pseudo) {
            makeMove(m);
            if (!leftKingInCheck()) {
                legal.add(m);
            }
            unmakeMove();
        }
        return legal;
    }

    /**
     * Generates moves that obey piece movement rules but may leave the king in check.
     * With {@code capturesOnly}, only captures and queen promotions are produced.
     */
    public List<Move> generatePseudoLegalMoves(boolean capturesOnly) {
        List<Move> moves = new ArrayList<>(capturesOnly ? 16 : 48);
        for (int sq = 0; sq < 64; sq++) {
            int p = squares[sq];
            if (p == 0 || (p > 0) != (side == WHITE)) {
                continue;
            }
            switch (Math.abs(p)) {
                case PAWN -> genPawnMoves(sq, moves, capturesOnly);
                case KNIGHT -> genStepMoves(sq, KNIGHT_DIRS, moves, capturesOnly);
                case BISHOP -> genSlideMoves(sq, BISHOP_DIRS, moves, capturesOnly);
                case ROOK -> genSlideMoves(sq, ROOK_DIRS, moves, capturesOnly);
                case QUEEN -> {
                    genSlideMoves(sq, BISHOP_DIRS, moves, capturesOnly);
                    genSlideMoves(sq, ROOK_DIRS, moves, capturesOnly);
                }
                case KING -> {
                    genStepMoves(sq, KING_DIRS, moves, capturesOnly);
                    if (!capturesOnly) {
                        genCastling(moves);
                    }
                }
                default -> throw new IllegalStateException("Bad piece " + p);
            }
        }
        return moves;
    }

    private void genPawnMoves(int from, List<Move> moves, boolean capturesOnly) {
        int f = from & 7, r = from >> 3;
        int r1 = r + side;
        if (r1 < 0 || r1 > 7) {
            return;
        }
        int startRank = side == WHITE ? 1 : 6;
        int promoRank = side == WHITE ? 7 : 0;

        int to = r1 * 8 + f;
        if (squares[to] == 0) {
            if (r1 == promoRank) {
                addPromotions(from, to, Move.QUIET, moves, capturesOnly);
            } else if (!capturesOnly) {
                moves.add(new Move(from, to, 0, Move.QUIET));
                int to2 = to + 8 * side;
                if (r == startRank && squares[to2] == 0) {
                    moves.add(new Move(from, to2, 0, Move.DOUBLE_PUSH));
                }
            }
        }
        for (int df = -1; df <= 1; df += 2) {
            int tf = f + df;
            if (tf < 0 || tf > 7) {
                continue;
            }
            to = r1 * 8 + tf;
            int target = squares[to];
            if (target * side < 0) {
                if (r1 == promoRank) {
                    addPromotions(from, to, Move.CAPTURE, moves, capturesOnly);
                } else {
                    moves.add(new Move(from, to, 0, Move.CAPTURE));
                }
            } else if (to == epSquare) {
                moves.add(new Move(from, to, 0, Move.CAPTURE | Move.EN_PASSANT));
            }
        }
    }

    private static void addPromotions(int from, int to, int flags, List<Move> moves, boolean queenOnly) {
        moves.add(new Move(from, to, QUEEN, flags));
        if (!queenOnly) {
            moves.add(new Move(from, to, KNIGHT, flags));
            moves.add(new Move(from, to, ROOK, flags));
            moves.add(new Move(from, to, BISHOP, flags));
        }
    }

    private void genStepMoves(int from, int[][] dirs, List<Move> moves, boolean capturesOnly) {
        int f = from & 7, r = from >> 3;
        for (int[] d : dirs) {
            int tf = f + d[0], tr = r + d[1];
            if (!onBoard(tf, tr)) {
                continue;
            }
            int to = tr * 8 + tf;
            int target = squares[to];
            if (target == 0) {
                if (!capturesOnly) {
                    moves.add(new Move(from, to, 0, Move.QUIET));
                }
            } else if (target * side < 0) {
                moves.add(new Move(from, to, 0, Move.CAPTURE));
            }
        }
    }

    private void genSlideMoves(int from, int[][] dirs, List<Move> moves, boolean capturesOnly) {
        int f = from & 7, r = from >> 3;
        for (int[] d : dirs) {
            int tf = f + d[0], tr = r + d[1];
            while (onBoard(tf, tr)) {
                int to = tr * 8 + tf;
                int target = squares[to];
                if (target == 0) {
                    if (!capturesOnly) {
                        moves.add(new Move(from, to, 0, Move.QUIET));
                    }
                } else {
                    if (target * side < 0) {
                        moves.add(new Move(from, to, 0, Move.CAPTURE));
                    }
                    break;
                }
                tf += d[0];
                tr += d[1];
            }
        }
    }

    private void genCastling(List<Move> moves) {
        int base = side == WHITE ? 0 : 56;
        int kingRight = side == WHITE ? WK_CASTLE : BK_CASTLE;
        int queenRight = side == WHITE ? WQ_CASTLE : BQ_CASTLE;
        int king = base + 4;
        if (squares[king] != KING * side) {
            return;
        }
        int enemy = -side;
        if ((castling & kingRight) != 0 && squares[base + 7] == ROOK * side
                && squares[base + 5] == 0 && squares[base + 6] == 0
                && !isSquareAttacked(king, enemy) && !isSquareAttacked(base + 5, enemy)
                && !isSquareAttacked(base + 6, enemy)) {
            moves.add(new Move(king, base + 6, 0, Move.CASTLE));
        }
        if ((castling & queenRight) != 0 && squares[base] == ROOK * side
                && squares[base + 1] == 0 && squares[base + 2] == 0 && squares[base + 3] == 0
                && !isSquareAttacked(king, enemy) && !isSquareAttacked(base + 3, enemy)
                && !isSquareAttacked(base + 2, enemy)) {
            moves.add(new Move(king, base + 2, 0, Move.CASTLE));
        }
    }

    // ------------------------------------------------------------------ draws & game state

    /** True if the current position occurred before since the last irreversible move. */
    public boolean isRepetition() {
        int n = history.size();
        for (int i = n - 2; i >= 0 && i >= n - halfmoveClock; i -= 2) {
            if (history.get(i).hash == hash) {
                return true;
            }
        }
        return false;
    }

    public boolean isThreefoldRepetition() {
        int n = history.size();
        int count = 1;
        for (int i = n - 2; i >= 0 && i >= n - halfmoveClock; i -= 2) {
            if (history.get(i).hash == hash && ++count >= 3) {
                return true;
            }
        }
        return false;
    }

    public boolean isInsufficientMaterial() {
        int minors = 0;
        for (int p : squares) {
            int type = Math.abs(p);
            if (type == PAWN || type == ROOK || type == QUEEN) {
                return false;
            }
            if (type == KNIGHT || type == BISHOP) {
                minors++;
            }
        }
        return minors <= 1;
    }

    boolean hasNonPawnMaterial(int color) {
        for (int p : squares) {
            int type = p * color;
            if (type >= KNIGHT && type <= QUEEN) {
                return true;
            }
        }
        return false;
    }

    /** Returns a description of the game result, or null if the game is still in progress. */
    public String gameResult() {
        if (generateLegalMoves().isEmpty()) {
            if (inCheck()) {
                return side == WHITE ? "0-1 (Black wins by checkmate)" : "1-0 (White wins by checkmate)";
            }
            return "1/2-1/2 (stalemate)";
        }
        if (halfmoveClock >= 100) return "1/2-1/2 (fifty-move rule)";
        if (isThreefoldRepetition()) return "1/2-1/2 (threefold repetition)";
        if (isInsufficientMaterial()) return "1/2-1/2 (insufficient material)";
        return null;
    }

    // ------------------------------------------------------------------ notation

    /** Finds the legal move matching UCI ("e2e4", "e7e8q") or SAN ("Nf3", "exd5", "O-O") notation. */
    public Move parseMove(String text) {
        String input = text.trim();
        String san = normalizeSan(input);
        for (Move m : generateLegalMoves()) {
            if (m.toUci().equalsIgnoreCase(input) || normalizeSan(toSan(m)).equals(san)) {
                return m;
            }
        }
        return null;
    }

    private static String normalizeSan(String s) {
        return s.replace("+", "").replace("#", "").replace("!", "").replace("?", "")
                .replace("0-0-0", "O-O-O").replace("0-0", "O-O").replace("=", "");
    }

    /** Standard algebraic notation for a legal move in the current position. */
    public String toSan(Move m) {
        StringBuilder sb = new StringBuilder();
        if ((m.flags & Move.CASTLE) != 0) {
            sb.append(m.to > m.from ? "O-O" : "O-O-O");
        } else {
            int type = Math.abs(squares[m.from]);
            if (type == PAWN) {
                if (m.isCapture()) {
                    sb.append((char) ('a' + (m.from & 7)));
                }
            } else {
                sb.append(PIECE_CHARS.charAt(type));
                boolean ambiguous = false, sameFile = false, sameRank = false;
                for (Move other : generateLegalMoves()) {
                    if (other.to == m.to && other.from != m.from && Math.abs(squares[other.from]) == type) {
                        ambiguous = true;
                        sameFile |= (other.from & 7) == (m.from & 7);
                        sameRank |= (other.from >> 3) == (m.from >> 3);
                    }
                }
                if (ambiguous) {
                    if (!sameFile) {
                        sb.append((char) ('a' + (m.from & 7)));
                    } else if (!sameRank) {
                        sb.append((char) ('1' + (m.from >> 3)));
                    } else {
                        sb.append(squareName(m.from));
                    }
                }
            }
            if (m.isCapture()) {
                sb.append('x');
            }
            sb.append(squareName(m.to));
            if (m.promotion != 0) {
                sb.append('=').append(PIECE_CHARS.charAt(m.promotion));
            }
        }
        makeMove(m);
        if (inCheck()) {
            sb.append(generateLegalMoves().isEmpty() ? '#' : '+');
        }
        unmakeMove();
        return sb.toString();
    }

    /** ASCII diagram of the board from the given perspective. */
    public String toDiagram(boolean whiteAtBottom) {
        StringBuilder sb = new StringBuilder();
        sb.append("    +-----------------+\n");
        for (int i = 0; i < 8; i++) {
            int rank = whiteAtBottom ? 7 - i : i;
            sb.append("  ").append(rank + 1).append(" | ");
            for (int j = 0; j < 8; j++) {
                int file = whiteAtBottom ? j : 7 - j;
                int p = squares[rank * 8 + file];
                char c = PIECE_CHARS.charAt(Math.abs(p));
                sb.append(p < 0 ? Character.toLowerCase(c) : c).append(' ');
            }
            sb.append("|\n");
        }
        sb.append("    +-----------------+\n      ");
        for (int j = 0; j < 8; j++) {
            sb.append((char) ('a' + (whiteAtBottom ? j : 7 - j))).append(' ');
        }
        return sb.append('\n').toString();
    }
}
