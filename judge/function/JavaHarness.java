import java.io.BufferedInputStream;

// 제출 파일: public class Main { public static boolean sol(int[][] matrix, int[] target) { ... } }
public class JavaHarness {
    private static final BufferedInputStream input = new BufferedInputStream(System.in);
    private static int nextInt() throws Exception {
        int c;
        do { c = input.read(); } while (c <= 32 && c != -1);
        if (c == -1) throw new IllegalArgumentException("Missing FUNCTION input");
        int value = 0;
        do {
            if (c < '0' || c > '9') throw new IllegalArgumentException("Invalid FUNCTION input");
            value = value * 10 + c - '0';
            c = input.read();
        } while (c > 32);
        return value;
    }
    public static void main(String[] args) throws Exception {
        int[][] matrix = new int[15][15];
        for (int r = 0; r < 15; r++) for (int c = 0; c < 15; c++) matrix[r][c] = nextInt();
        int[] target = {nextInt(), nextInt()};
        System.out.println(Main.sol(matrix, target));
    }
}
