package chess;

/** A single chess move. Squares are indexed 0..63 with a1 = 0, h1 = 7, a8 = 56. */
public final class Move {
    public static final int QUIET = 0;
    public static final int CAPTURE = 1;
    public static final int DOUBLE_PUSH = 2;
    public static final int EN_PASSANT = 4;
    public static final int CASTLE = 8;

    public final int from;
    public final int to;
    /** Promotion piece type (Board.KNIGHT..Board.QUEEN), or 0 when not a promotion. */
    public final int promotion;
    public final int flags;

    /** Scratch value used by the search for move ordering. */
    int orderScore;

    public Move(int from, int to, int promotion, int flags) {
        this.from = from;
        this.to = to;
        this.promotion = promotion;
        this.flags = flags;
    }

    public boolean isCapture() {
        return (flags & CAPTURE) != 0;
    }

    public boolean isQuiet() {
        return !isCapture() && promotion == 0;
    }

    public String toUci() {
        StringBuilder sb = new StringBuilder(5);
        sb.append(Board.squareName(from)).append(Board.squareName(to));
        if (promotion != 0) {
            sb.append(Character.toLowerCase(Board.PIECE_CHARS.charAt(promotion)));
        }
        return sb.toString();
    }

    public boolean sameAs(Move other) {
        return other != null && from == other.from && to == other.to && promotion == other.promotion;
    }

    @Override
    public String toString() {
        return toUci();
    }
}
