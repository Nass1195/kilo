package chess;

import java.util.ArrayList;
import java.util.List;

/**
 * Iterative-deepening alpha-beta (principal variation search) with a transposition table,
 * quiescence search, null-move pruning, late move reductions and killer/history ordering.
 */
public final class Searcher {
    public static final int INF = 1_000_000;
    public static final int MATE = 100_000;
    private static final int MATE_BOUND = MATE - 1000;
    private static final int MAX_PLY = 128;

    private static final int TT_EXACT = 0, TT_LOWER = 1, TT_UPPER = 2;

    public record Result(Move bestMove, int score, int depth, long nodes, long timeMs, List<Move> pv) {
    }

    /** Receives a report after each completed iteration. */
    public interface Listener {
        void onIteration(Result result);
    }

    private static final class TTEntry {
        long key;
        int depth;
        int score;
        int flag;
        Move move;
    }

    private final TTEntry[] table;
    private final int tableMask;
    private final Move[][] killers = new Move[MAX_PLY][2];
    private final int[][] historyScores = new int[64][64];

    private Board board;
    private long nodes;
    private long startTime;
    private long hardDeadline;
    private volatile boolean stopRequested;
    private boolean aborted;
    private Move rootBest;
    private int rootBestScore;

    public Searcher() {
        this(20);
    }

    /** @param tableBits log2 of the transposition table entry count */
    public Searcher(int tableBits) {
        table = new TTEntry[1 << tableBits];
        tableMask = table.length - 1;
    }

    public void clear() {
        java.util.Arrays.fill(table, null);
        for (Move[] k : killers) {
            k[0] = k[1] = null;
        }
        for (int[] h : historyScores) {
            java.util.Arrays.fill(h, 0);
        }
    }

    /** Requests that a running search stop as soon as possible. */
    public void stop() {
        stopRequested = true;
    }

    /**
     * Searches the position and returns the best move found.
     *
     * @param maxDepth maximum depth in plies
     * @param timeMs   time budget in milliseconds, or 0 for no limit
     */
    public Result search(Board board, int maxDepth, long timeMs, Listener listener) {
        this.board = board;
        nodes = 0;
        stopRequested = false;
        aborted = false;
        startTime = System.currentTimeMillis();
        hardDeadline = timeMs > 0 ? startTime + timeMs : Long.MAX_VALUE;
        long softDeadline = timeMs > 0 ? startTime + timeMs / 2 : Long.MAX_VALUE;
        for (int[] h : historyScores) {
            java.util.Arrays.fill(h, 0);
        }

        List<Move> legal = board.generateLegalMoves();
        if (legal.isEmpty()) {
            return new Result(null, board.inCheck() ? -MATE : 0, 0, 0, 0, List.of());
        }

        Result result = new Result(legal.get(0), 0, 0, 0, 0, List.of(legal.get(0)));
        rootBest = null;
        for (int depth = 1; depth <= Math.max(1, maxDepth); depth++) {
            int score = negamax(depth, 0, -INF, INF, true);
            if (aborted) {
                // A root move that already beat the previous best is still trustworthy.
                if (rootBest != null && !rootBest.sameAs(result.bestMove())) {
                    result = new Result(rootBest, rootBestScore, depth, nodes, elapsed(), List.of(rootBest));
                }
                break;
            }
            result = new Result(rootBest, score, depth, nodes, elapsed(), extractPv(depth));
            if (listener != null) {
                listener.onIteration(result);
            }
            if (Math.abs(score) >= MATE_BOUND || System.currentTimeMillis() >= softDeadline || legal.size() == 1) {
                break;
            }
        }
        return new Result(result.bestMove(), result.score(), result.depth(), nodes, elapsed(), result.pv());
    }

    private long elapsed() {
        return System.currentTimeMillis() - startTime;
    }

    private void checkTime() {
        if (stopRequested || System.currentTimeMillis() >= hardDeadline) {
            aborted = true;
        }
    }

    private int negamax(int depth, int ply, int alpha, int beta, boolean allowNull) {
        if ((++nodes & 2047) == 0) {
            checkTime();
        }
        if (aborted) {
            return 0;
        }
        boolean isPv = beta - alpha > 1;

        if (ply > 0) {
            if (board.halfmoveClock >= 100 || board.isRepetition() || board.isInsufficientMaterial()) {
                return 0;
            }
            // Mate distance pruning: no line from here can beat an already found shorter mate.
            alpha = Math.max(alpha, -MATE + ply);
            beta = Math.min(beta, MATE - ply - 1);
            if (alpha >= beta) {
                return alpha;
            }
        }
        if (ply >= MAX_PLY - 1) {
            return Evaluator.evaluate(board);
        }

        boolean inCheck = board.inCheck();
        if (inCheck) {
            depth++;
        }
        if (depth <= 0) {
            return quiescence(ply, alpha, beta);
        }

        TTEntry entry = table[(int) board.hash & tableMask];
        Move ttMove = null;
        if (entry != null && entry.key == board.hash) {
            ttMove = entry.move;
            if (ply > 0 && entry.depth >= depth) {
                int ttScore = scoreFromTT(entry.score, ply);
                if (entry.flag == TT_EXACT
                        || (entry.flag == TT_LOWER && ttScore >= beta)
                        || (entry.flag == TT_UPPER && ttScore <= alpha)) {
                    return ttScore;
                }
            }
        }

        // Null-move pruning: if passing still fails high, the position is good enough.
        if (allowNull && !isPv && !inCheck && depth >= 3 && board.hasNonPawnMaterial(board.side)
                && Evaluator.evaluate(board) >= beta) {
            board.makeNullMove();
            int score = -negamax(depth - 3, ply + 1, -beta, -beta + 1, false);
            board.unmakeNullMove();
            if (aborted) {
                return 0;
            }
            if (score >= beta) {
                return score >= MATE_BOUND ? beta : score;
            }
        }

        List<Move> moves = board.generatePseudoLegalMoves(false);
        scoreMoves(moves, ttMove, ply);

        int originalAlpha = alpha;
        int bestScore = -INF;
        Move bestMove = null;
        int legalCount = 0;

        for (int i = 0; i < moves.size(); i++) {
            Move m = pickNext(moves, i);
            board.makeMove(m);
            if (board.leftKingInCheck()) {
                board.unmakeMove();
                continue;
            }
            legalCount++;

            int score;
            if (legalCount == 1) {
                score = -negamax(depth - 1, ply + 1, -beta, -alpha, true);
            } else {
                int reduction = 0;
                if (depth >= 3 && legalCount > 4 && m.isQuiet() && !inCheck && !board.inCheck()) {
                    reduction = legalCount > 10 ? 2 : 1;
                }
                score = -negamax(depth - 1 - reduction, ply + 1, -alpha - 1, -alpha, true);
                if (score > alpha && reduction > 0) {
                    score = -negamax(depth - 1, ply + 1, -alpha - 1, -alpha, true);
                }
                if (score > alpha && score < beta) {
                    score = -negamax(depth - 1, ply + 1, -beta, -alpha, true);
                }
            }
            board.unmakeMove();
            if (aborted) {
                return 0;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMove = m;
                if (score > alpha) {
                    alpha = score;
                    if (ply == 0) {
                        rootBest = m;
                        rootBestScore = score;
                    }
                    if (alpha >= beta) {
                        if (m.isQuiet()) {
                            if (!m.sameAs(killers[ply][0])) {
                                killers[ply][1] = killers[ply][0];
                                killers[ply][0] = m;
                            }
                            historyScores[m.from][m.to] += depth * depth;
                        }
                        break;
                    }
                }
            }
        }

        if (legalCount == 0) {
            return inCheck ? -MATE + ply : 0;
        }

        int flag = bestScore <= originalAlpha ? TT_UPPER : bestScore >= beta ? TT_LOWER : TT_EXACT;
        storeTT(depth, scoreToTT(bestScore, ply), flag, bestMove);
        return bestScore;
    }

    private int quiescence(int ply, int alpha, int beta) {
        if ((++nodes & 2047) == 0) {
            checkTime();
        }
        if (aborted) {
            return 0;
        }
        int standPat = Evaluator.evaluate(board);
        if (ply >= MAX_PLY - 1 || standPat >= beta) {
            return standPat;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        List<Move> moves = board.generatePseudoLegalMoves(true);
        scoreMoves(moves, null, ply);
        for (int i = 0; i < moves.size(); i++) {
            Move m = pickNext(moves, i);
            board.makeMove(m);
            if (board.leftKingInCheck()) {
                board.unmakeMove();
                continue;
            }
            int score = -quiescence(ply + 1, -beta, -alpha);
            board.unmakeMove();
            if (aborted) {
                return 0;
            }
            if (score >= beta) {
                return score;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    // ------------------------------------------------------------------ move ordering

    private void scoreMoves(List<Move> moves, Move ttMove, int ply) {
        for (Move m : moves) {
            if (m.sameAs(ttMove)) {
                m.orderScore = 10_000_000;
            } else if (m.isCapture()) {
                int victim = (m.flags & Move.EN_PASSANT) != 0 ? Board.PAWN : Math.abs(board.squares[m.to]);
                int attacker = Math.abs(board.squares[m.from]);
                m.orderScore = 1_000_000 + Evaluator.PIECE_VALUES[victim] * 10 - Evaluator.PIECE_VALUES[attacker] / 10
                        + Evaluator.PIECE_VALUES[m.promotion];
            } else if (m.promotion != 0) {
                m.orderScore = 900_000 + Evaluator.PIECE_VALUES[m.promotion];
            } else if (m.sameAs(killers[ply][0])) {
                m.orderScore = 800_000;
            } else if (m.sameAs(killers[ply][1])) {
                m.orderScore = 700_000;
            } else {
                m.orderScore = Math.min(historyScores[m.from][m.to], 600_000);
            }
        }
    }

    /** Selection sort step: swaps the highest-scored remaining move into position i. */
    private static Move pickNext(List<Move> moves, int i) {
        int best = i;
        for (int j = i + 1; j < moves.size(); j++) {
            if (moves.get(j).orderScore > moves.get(best).orderScore) {
                best = j;
            }
        }
        Move m = moves.get(best);
        moves.set(best, moves.get(i));
        moves.set(i, m);
        return m;
    }

    // ------------------------------------------------------------------ transposition table

    private void storeTT(int depth, int score, int flag, Move move) {
        int index = (int) board.hash & tableMask;
        TTEntry e = table[index];
        if (e == null) {
            e = new TTEntry();
            table[index] = e;
        } else if (e.key == board.hash && e.depth > depth && flag != TT_EXACT) {
            return;
        }
        e.key = board.hash;
        e.depth = depth;
        e.score = score;
        e.flag = flag;
        e.move = move;
    }

    private static int scoreToTT(int score, int ply) {
        if (score >= MATE_BOUND) return score + ply;
        if (score <= -MATE_BOUND) return score - ply;
        return score;
    }

    private static int scoreFromTT(int score, int ply) {
        if (score >= MATE_BOUND) return score - ply;
        if (score <= -MATE_BOUND) return score + ply;
        return score;
    }

    /** Follows best moves stored in the transposition table to build the principal variation. */
    private List<Move> extractPv(int maxLength) {
        List<Move> pv = new ArrayList<>();
        if (rootBest == null) {
            return pv;
        }
        pv.add(rootBest);
        board.makeMove(rootBest);
        while (pv.size() < maxLength) {
            TTEntry e = table[(int) board.hash & tableMask];
            if (e == null || e.key != board.hash || e.move == null) {
                break;
            }
            Move next = null;
            for (Move m : board.generateLegalMoves()) {
                if (m.sameAs(e.move)) {
                    next = m;
                    break;
                }
            }
            if (next == null || board.isRepetition()) {
                break;
            }
            pv.add(next);
            board.makeMove(next);
        }
        for (int i = 0; i < pv.size(); i++) {
            board.unmakeMove();
        }
        return pv;
    }

    /** Converts a score to "mate N" if it represents a forced mate, otherwise null. */
    public static Integer mateIn(int score) {
        if (score >= MATE_BOUND) return (MATE - score + 1) / 2;
        if (score <= -MATE_BOUND) return -(MATE + score) / 2;
        return null;
    }
}
