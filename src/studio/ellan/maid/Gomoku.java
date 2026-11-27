package studio.ellan.maid;

import io.github.tlm.folia.api.BoardGame;
import io.github.tlm.folia.api.Cell;

import java.util.ArrayList;
import java.util.List;

/** Five in a row on a 15x15 board; the player plays black, the maid white. FEN = 225 cells ('.', 'b', 'w') + side to move. */
final class Gomoku implements BoardGame {
    static final int N = 15;
    static final double SPACING = 0.1316, ORIGIN = -0.9212;
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    @Override public String id() { return "gomoku"; }
    @Override public String initialFen() { return ".".repeat(N * N) + "b"; }

    @Override public int clickToSquare(double lx, double lz) {
        double gx = (lx - ORIGIN) / SPACING, gz = (lz - ORIGIN) / SPACING;
        int c = (int) Math.round(gx), r = (int) Math.round(gz);
        if (Math.abs(c - gx) > 0.5 || Math.abs(r - gz) > 0.5 || c < 0 || r < 0 || c >= N || r >= N) return -1;
        return r * N + c;
    }
    @Override public Cell squareToCell(int sq) { return new Cell(sq % N, sq / N); }
    @Override public double originX() { return ORIGIN; }
    @Override public double originZ() { return ORIGIN; }
    @Override public int cellToSquare(int c, int r) { return c < 0 || r < 0 || c >= N || r >= N ? -1 : r * N + c; }
    @Override public boolean mirroredRows() { return false; }

    @Override public List<Placed> pieces(String fen) {
        List<Placed> out = new ArrayList<>();
        for (int i = 0; i < N * N; i++) {
            String it = pieceAt(fen, i % N, i / N);
            if (it != null) out.add(new Placed(i % N, i / N, it));
        }
        return out;
    }
    @Override public String pieceAt(String fen, int c, int r) {
        char ch = fen.charAt(r * N + c);
        return ch == 'b' ? "gomoku_piece_black" : ch == 'w' ? "gomoku_piece_white" : null;
    }

    @Override public Result click(String fen, int selected, int sq) {
        if (sq < 0 || !isPlayerTurn(fen) || isMate(fen) || fen.charAt(sq) != '.') return new Result(Action.IGNORE, selected, null);
        String after = put(fen, sq, 'b', 'w');
        boolean win = five(after, 'b');
        return new Result(Action.MOVE, -1, new Move(fen, after, sq % N, sq / N, sq % N, sq / N, false, false, win));
    }

    @Override public Move think(String fen, long ms) {
        int best = -1; long bestScore = Long.MIN_VALUE;
        boolean empty = fen.indexOf('b') < 0 && fen.indexOf('w') < 0;
        for (int i = 0; i < N * N; i++) {
            if (fen.charAt(i) != '.') continue;
            if (!empty && !near(fen, i)) continue;
            long sc = score(fen, i, 'w') * 11 / 10 + score(fen, i, 'b') - Math.abs(i % N - 7) - Math.abs(i / N - 7);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        if (best < 0) return null;
        String after = put(fen, best, 'w', 'b');
        return new Move(fen, after, best % N, best / N, best % N, best / N, false, false, five(after, 'w'));
    }

    @Override public boolean isCheck(String fen) { return false; }
    @Override public boolean isMate(String fen) { return five(fen, 'b') || five(fen, 'w'); }
    @Override public boolean isPlayerTurn(String fen) { return fen.charAt(N * N) == 'b'; }
    @Override public boolean isDraw(String fen) { return fen.indexOf('.') < 0 || fen.indexOf('.') >= N * N; }

    static String put(String fen, int sq, char me, char next) {
        char[] a = fen.toCharArray(); a[sq] = me; a[N * N] = next; return new String(a);
    }
    static boolean near(String fen, int i) {
        int c = i % N, r = i / N;
        for (int dc = -2; dc <= 2; dc++) for (int dr = -2; dr <= 2; dr++) {
            int cc = c + dc, rr = r + dr;
            if (cc >= 0 && rr >= 0 && cc < N && rr < N && fen.charAt(rr * N + cc) != '.') return true;
        }
        return false;
    }
    static boolean five(String fen, char who) {
        for (int i = 0; i < N * N; i++) {
            if (fen.charAt(i) != who) continue;
            for (int[] d : DIRS) if (run(fen, i % N, i / N, d, who) >= 5) return true;
        }
        return false;
    }
    static int run(String fen, int c, int r, int[] d, char who) {
        int n = 0;
        while (c >= 0 && r >= 0 && c < N && r < N && fen.charAt(r * N + c) == who) { n++; c += d[0]; r += d[1]; }
        return n;
    }
    /** value of placing 'who' on i: longest line through it, weighted by open ends */
    static long score(String fen, int i, char who) {
        int c0 = i % N, r0 = i / N; long total = 0;
        for (int[] d : DIRS) {
            int n = 1, open = 0;
            for (int s = -1; s <= 1; s += 2) {
                int c = c0 + s * d[0], r = r0 + s * d[1];
                while (c >= 0 && r >= 0 && c < N && r < N && fen.charAt(r * N + c) == who) { n++; c += s * d[0]; r += s * d[1]; }
                if (c >= 0 && r >= 0 && c < N && r < N && fen.charAt(r * N + c) == '.') open++;
            }
            if (n >= 5) total += 1_000_000;
            else if (open > 0) total += switch (n) { case 4 -> open == 2 ? 50_000 : 5_000; case 3 -> open == 2 ? 3_000 : 200; case 2 -> open == 2 ? 100 : 10; default -> 1; };
        }
        return total;
    }
}
