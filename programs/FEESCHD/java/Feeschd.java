import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class Feeschd {
    // Working storage
    private static String wsFeeStatus = "00";
    private static String wsChgStatus = "00";
    private static boolean endOfAccounts = false;
    private static final BigDecimal WS_WAIVER_BAL = new BigDecimal("5000.00");
    private static final BigDecimal WS_PER_TXN = new BigDecimal("0.35");
    private static final BigDecimal WS_OD_RATE = new BigDecimal("0.1899");
    private static final BigDecimal WS_CAP = new BigDecimal("40.00");
    private static BigDecimal wsBase = BigDecimal.ZERO;
    private static int wsFreeTxns = 0;
    private static BigDecimal wsExcessRaw = BigDecimal.ZERO;
    private static long wsExcessScaled = 0; // S9(5)
    private static BigDecimal wsTxnFee = BigDecimal.ZERO;
    private static BigDecimal wsOdInt = BigDecimal.ZERO;
    private static BigDecimal wsTotal = BigDecimal.ZERO;
    private static String wsCode = "OK";
    private static long wsReadCnt = 0;
    private static long wsCappedCnt = 0;
    // Current fee record fields
    private static byte[] curAcctId = new byte[10];
    private static char feeProduct = ' ';
    private static BigDecimal feeAvgBal = BigDecimal.ZERO;
    private static int feeTxnCount = 0;
    private static int feeMonthDays = 0;
    private static boolean haveRecord = false;
    // Files
    private static InputStream feeIn = null;
    private static OutputStream chgOut = null;
    private static final int FEE_LEN = 29;
    private static final int CHG_LEN = 38;

    public static void main(String[] args) throws Exception {
        n0000Main();
    }

    private static void n0000Main() throws Exception {
        n1000Init();
        while (!endOfAccounts) {
            n2000Process();
        }
        n9000Term();
    }

    private static void n1000Init() throws Exception {
        String feePath = System.getenv("DD_FEEIN");
        String chgPath = System.getenv("DD_CHGOUT");
        if (feePath == null) {
            wsFeeStatus = "35";
            System.out.println("FEESCHD OPEN FEEIN FAILED " + wsFeeStatus);
            System.exit(16);
        }
        try {
            feeIn = new BufferedInputStream(new FileInputStream(feePath));
        } catch (IOException e) {
            wsFeeStatus = "35";
            System.out.println("FEESCHD OPEN FEEIN FAILED " + wsFeeStatus);
            System.exit(16);
        }
        wsFeeStatus = "00";
        if (chgPath == null) {
            wsChgStatus = "35";
            System.out.println("FEESCHD OPEN CHGOUT FAILED " + wsChgStatus);
            System.exit(16);
        }
        try {
            Path p = Paths.get(chgPath);
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            chgOut = new BufferedOutputStream(new FileOutputStream(chgPath, false));
        } catch (IOException e) {
            wsChgStatus = "35";
            System.out.println("FEESCHD OPEN CHGOUT FAILED " + wsChgStatus);
            System.exit(16);
        }
        wsChgStatus = "00";
        n8000Read();
    }

    private static void n2000Process() throws Exception {
        wsReadCnt = (wsReadCnt + 1) % 10000000L;
        wsCode = "OK";
        n3000BaseFee();
        n4000TxnFee();
        n5000OdInterest();
        n6000Total();
        n7000WriteResult();
        n8000Read();
    }

    private static void n3000BaseFee() {
        if (feeProduct == 'A') {
            wsBase = new BigDecimal("6.00");
            wsFreeTxns = 10;
        } else if (feeProduct == 'B') {
            wsBase = new BigDecimal("3.50");
            wsFreeTxns = 25;
        } else {
            wsBase = BigDecimal.ZERO.setScale(2);
            wsFreeTxns = 9999;
        }
        // IF FEE-AVG-BAL NOT < WS-WAIVER-BAL AND WS-BASE > ZERO
        if (feeAvgBal.compareTo(WS_WAIVER_BAL) >= 0 && wsBase.compareTo(BigDecimal.ZERO) > 0) {
            wsBase = BigDecimal.ZERO.setScale(2);
            wsCode = "WV";
        }
    }

    private static void n4000TxnFee() {
        long excess = (long) feeTxnCount - (long) wsFreeTxns;
        // store into S9(5) with TRUNC(STD) wrap
        excess = wrapScaled(excess, 5);
        wsExcessScaled = excess;
        wsExcessRaw = BigDecimal.valueOf(excess);
        if (wsExcessScaled > 0) {
            BigDecimal exact = BigDecimal.valueOf(wsExcessScaled).multiply(WS_PER_TXN);
            wsTxnFee = storeTruncated(exact, 5, 2);
        } else {
            wsTxnFee = BigDecimal.ZERO.setScale(2);
        }
    }

    private static void n5000OdInterest() {
        if (feeAvgBal.compareTo(BigDecimal.ZERO) < 0) {
            // ZERO - FEE-AVG-BAL * WS-OD-RATE * FEE-MONTH-DAYS / 365 rounded
            BigDecimal negBal = feeAvgBal.negate();
            BigDecimal num = negBal.multiply(WS_OD_RATE).multiply(BigDecimal.valueOf(feeMonthDays));
            BigDecimal q = num.divide(new BigDecimal("365"), 50, RoundingMode.HALF_UP);
            BigDecimal rounded = q.setScale(2, RoundingMode.HALF_UP);
            wsOdInt = storeTruncated(rounded, 7, 2);
        } else {
            wsOdInt = BigDecimal.ZERO.setScale(2);
        }
    }

    private static void n6000Total() {
        BigDecimal sum = wsBase.add(wsTxnFee).add(wsOdInt);
        wsTotal = storeTruncated(sum, 5, 2);
        boolean isPrivate = (feeProduct == 'C');
        if (!isPrivate && wsTotal.compareTo(WS_CAP) > 0) {
            wsTotal = WS_CAP;
            wsCode = "CP";
            wsCappedCnt = (wsCappedCnt + 1) % 10000000L;
        }
    }

    private static void n7000WriteResult() throws Exception {
        byte[] rec = new byte[CHG_LEN];
        System.arraycopy(curAcctId, 0, rec, 0, 10);
        byte[] bBase = formatSignedSeparate(wsBase, 3, 2);
        byte[] bTxn = formatSignedSeparate(wsTxnFee, 3, 2);
        byte[] bOd = formatSignedSeparate(wsOdInt, 5, 2);
        byte[] bTot = formatSignedSeparate(wsTotal, 3, 2);
        System.arraycopy(bBase, 0, rec, 10, 6);
        System.arraycopy(bTxn, 0, rec, 16, 6);
        System.arraycopy(bOd, 0, rec, 22, 8);
        System.arraycopy(bTot, 0, rec, 30, 6);
        byte[] codeBytes = wsCode.getBytes(StandardCharsets.ISO_8859_1);
        rec[36] = codeBytes.length > 0 ? codeBytes[0] : (byte) ' ';
        rec[37] = codeBytes.length > 1 ? codeBytes[1] : (byte) ' ';
        chgOut.write(rec);
    }

    private static void n8000Read() throws Exception {
        byte[] buf = new byte[FEE_LEN];
        int off = 0;
        boolean eofAtStart = false;
        while (off < FEE_LEN) {
            int r = feeIn.read(buf, off, FEE_LEN - off);
            if (r == -1) {
                if (off == 0) eofAtStart = true;
                break;
            }
            off += r;
        }
        if (eofAtStart) {
            endOfAccounts = true;
            haveRecord = false;
            return;
        }
        if (off < FEE_LEN) {
            // short final record: pad with spaces (should not happen in tests)
            for (int i = off; i < FEE_LEN; i++) buf[i] = (byte) ' ';
        }
        parseFeeRec(buf);
        haveRecord = true;
    }

    private static void n9000Term() throws Exception {
        if (feeIn != null) try { feeIn.close(); } catch (IOException ignored) {}
        if (chgOut != null) try { chgOut.close(); } catch (IOException ignored) {}
        String rc = String.format("%07d", wsReadCnt % 10000000L);
        String cc = String.format("%07d", wsCappedCnt % 10000000L);
        System.out.println("FEESCHD ACCOUNTS=" + rc + " CAPPED=" + cc);
    }

    private static void parseFeeRec(byte[] buf) {
        System.arraycopy(buf, 0, curAcctId, 0, 10);
        feeProduct = (char) (buf[10] & 0xFF);
        // FEE-AVG-BAL 12 bytes at 11, SIGN LEADING SEPARATE S9(9)V99
        String sBal = new String(buf, 11, 12, StandardCharsets.ISO_8859_1);
        feeAvgBal = parseSignedSeparate(sBal, 9, 2);
        String sTxn = new String(buf, 23, 4, StandardCharsets.ISO_8859_1);
        String sDays = new String(buf, 27, 2, StandardCharsets.ISO_8859_1);
        feeTxnCount = parseUnsigned(sTxn);
        feeMonthDays = parseUnsigned(sDays);
    }

    private static BigDecimal parseSignedSeparate(String s, int intDigits, int decDigits) {
        if (s == null || s.length() == 0) return BigDecimal.ZERO.setScale(decDigits);
        int sign = 1;
        String digits = s;
        char c0 = s.charAt(0);
        if (c0 == '-') { sign = -1; digits = s.substring(1); }
        else if (c0 == '+') { sign = 1; digits = s.substring(1); }
        else if (c0 == ' ') { sign = 1; digits = s.substring(1); }
        else {
            // no sign char, treat whole as unsigned
            sign = 1;
            digits = s;
        }
        digits = digits.trim();
        if (digits.isEmpty()) digits = "0";
        // keep only digits (defensive)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        if (sb.length() == 0) sb.append('0');
        BigDecimal v = new BigDecimal(sb.toString()).movePointLeft(decDigits);
        if (sign < 0) v = v.negate();
        return v;
    }

    private static int parseUnsigned(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        if (sb.length() == 0) return 0;
        try { return Integer.parseInt(sb.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static long wrapScaled(long scaled, int totalDigits) {
        BigInteger mod = BigInteger.TEN.pow(totalDigits);
        BigInteger bi = BigInteger.valueOf(scaled);
        boolean neg = bi.signum() < 0;
        BigInteger a = bi.abs().mod(mod);
        if (a.equals(BigInteger.ZERO)) return 0L;
        return neg ? -a.longValue() : a.longValue();
    }

    private static BigDecimal storeTruncated(BigDecimal v, int totalDigits, int scale) {
        BigDecimal t = v.setScale(scale, RoundingMode.DOWN);
        BigInteger scaled = t.movePointRight(scale).toBigIntegerExact();
        BigInteger mod = BigInteger.TEN.pow(totalDigits);
        boolean neg = scaled.signum() < 0;
        BigInteger a = scaled.abs().mod(mod);
        if (a.equals(BigInteger.ZERO)) return BigDecimal.ZERO.setScale(scale);
        BigInteger res = neg ? a.negate() : a;
        return new BigDecimal(res).movePointLeft(scale).setScale(scale);
    }

    private static byte[] formatSignedSeparate(BigDecimal v, int intDigits, int decDigits) {
        int total = intDigits + decDigits;
        BigDecimal t = v.setScale(decDigits, RoundingMode.DOWN);
        BigInteger scaled;
        try { scaled = t.movePointRight(decDigits).toBigIntegerExact(); }
        catch (ArithmeticException e) { scaled = BigInteger.ZERO; }
        char sign = scaled.signum() < 0 ? '-' : '+';
        BigInteger abs = scaled.abs();
        String d = abs.toString();
        // in case of overflow not yet wrapped, keep low-order digits
        if (d.length() > total) d = d.substring(d.length() - total);
        while (d.length() < total) d = "0" + d;
        String out = sign + d;
        return out.getBytes(StandardCharsets.ISO_8859_1);
    }
}
