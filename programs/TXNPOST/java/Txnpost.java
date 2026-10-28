import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class Txnpost {
    private String wsTxnStatus = "00";
    private String wsPostStatus = "00";
    private boolean endOfTxns = false;

    private final BigDecimal wsSurchargeRate = new BigDecimal("0.0125");
    private final BigDecimal wsSurchargeCap = new BigDecimal("15.00");
    private BigDecimal wsCharge = BigDecimal.ZERO.setScale(2);
    private BigDecimal wsCredit = BigDecimal.ZERO.setScale(2);
    private BigDecimal wsNewBal = BigDecimal.ZERO.setScale(2);
    private BigDecimal wsFloor = BigDecimal.ZERO.setScale(2);

    private int wsPostedCnt = 0;
    private int wsRejectCnt = 0;

    private byte[] txnBytes = new byte[0];
    private int txnPos = 0;
    private OutputStream postOut;

    private final byte[] txnId = new byte[9];
    private char txnType = ' ';
    private BigDecimal txnAmount = BigDecimal.ZERO.setScale(2);
    private BigDecimal txnBalance = BigDecimal.ZERO.setScale(2);
    private int txnOdLimit = 0;

    private String postStatus = "  ";

    public static void main(String[] args) throws Exception {
        Txnpost p = new Txnpost();
        p.main0000();
    }

    private void main0000() throws Exception {
        init1000();
        while (!endOfTxns) {
            process2000();
        }
        term9000();
    }

    private void init1000() throws Exception {
        String txnPath = System.getenv("DD_TXNIN");
        String postPath = System.getenv("DD_POSTOUT");
        try {
            if (txnPath == null || txnPath.isEmpty()) {
                throw new IOException("missing DD_TXNIN");
            }
            txnBytes = Files.readAllBytes(Paths.get(txnPath));
            wsTxnStatus = "00";
        } catch (IOException e) {
            wsTxnStatus = "35";
            System.out.println("TXNPOST OPEN TXNIN FAILED " + wsTxnStatus);
            System.exit(16);
            return;
        }
        txnPos = 0;
        try {
            if (postPath == null || postPath.isEmpty()) {
                throw new IOException("missing DD_POSTOUT");
            }
            Path p = Paths.get(postPath);
            postOut = Files.newOutputStream(p, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            wsPostStatus = "00";
        } catch (IOException e) {
            wsPostStatus = "35";
            System.out.println("TXNPOST OPEN POSTOUT FAILED " + wsPostStatus);
            System.exit(16);
            return;
        }
        read8000();
    }

    private void process2000() throws Exception {
        wsCharge = BigDecimal.ZERO.setScale(2);
        wsNewBal = txnBalance;
        if (txnType == 'P') {
            postPayment3000();
        } else if (txnType == 'F') {
            wsNewBal = overflowMod(txnBalance.subtract(txnAmount), 9, 2);
            postStatus = "FE";
        } else if (txnType == 'I') {
            wsCredit = overflowMod(txnAmount.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP), 7, 2);
            wsNewBal = overflowMod(txnBalance.add(wsCredit), 9, 2);
            postStatus = "IC";
        } else {
            postStatus = "RJ";
        }
        if (postStatus.equals("RJ") || postStatus.equals("NS")) {
            wsRejectCnt = (wsRejectCnt + 1) % 10_000_000;
        } else {
            wsPostedCnt = (wsPostedCnt + 1) % 10_000_000;
        }
        writeResult5000();
        read8000();
    }

    private void postPayment3000() {
        if (txnAmount.compareTo(BigDecimal.ZERO) <= 0) {
            postStatus = "RJ";
        } else {
            BigDecimal prod = txnAmount.multiply(wsSurchargeRate);
            BigDecimal ch = overflowMod(prod.setScale(2, RoundingMode.DOWN), 3, 2);
            wsCharge = ch;
            if (wsCharge.compareTo(wsSurchargeCap) > 0) {
                wsCharge = wsSurchargeCap;
            }
            wsNewBal = overflowMod(txnBalance.subtract(txnAmount).subtract(wsCharge), 9, 2);
            wsFloor = overflowMod(BigDecimal.valueOf(-(long) txnOdLimit).setScale(2), 9, 2);
            if (wsNewBal.compareTo(wsFloor) < 0) {
                wsNewBal = txnBalance;
                wsCharge = BigDecimal.ZERO.setScale(2);
                postStatus = "NS";
            } else {
                postStatus = "OK";
            }
        }
    }

    private void writeResult5000() throws Exception {
        byte[] rec = new byte[29];
        System.arraycopy(txnId, 0, rec, 0, 9);
        byte[] ch = formatSignedSeparate(wsCharge, 3, 2);
        System.arraycopy(ch, 0, rec, 9, 6);
        byte[] nb = formatSignedSeparate(wsNewBal, 9, 2);
        System.arraycopy(nb, 0, rec, 15, 12);
        rec[27] = (byte) postStatus.charAt(0);
        rec[28] = (byte) postStatus.charAt(1);
        postOut.write(rec);
    }

    private void read8000() {
        if (txnPos + 37 > txnBytes.length) {
            endOfTxns = true;
        } else {
            System.arraycopy(txnBytes, txnPos, txnId, 0, 9);
            txnType = (char) (txnBytes[txnPos + 9] & 0xFF);
            txnAmount = parseSignedSeparate(txnBytes, txnPos + 10, 7, 2);
            txnBalance = parseSignedSeparate(txnBytes, txnPos + 20, 9, 2);
            txnOdLimit = parseUnsigned(txnBytes, txnPos + 32, 5);
            txnPos += 37;
            wsTxnStatus = "00";
        }
    }

    private void term9000() throws Exception {
        if (postOut != null) {
            try {
                postOut.close();
            } catch (IOException ignored) {
            }
        }
        String posted = String.format("%07d", wsPostedCnt);
        String rejected = String.format("%07d", wsRejectCnt);
        System.out.println("TXNPOST POSTED=" + posted + " REJECTED=" + rejected);
    }

    private static BigDecimal overflowMod(BigDecimal v, int intDigits, int fracDigits) {
        BigInteger unscaled = v.movePointRight(fracDigits).toBigIntegerExact();
        BigInteger mod = BigInteger.TEN.pow(intDigits + fracDigits);
        BigInteger rem = unscaled.remainder(mod);
        if (rem.equals(BigInteger.ZERO)) {
            return BigDecimal.ZERO.setScale(fracDigits);
        }
        return new BigDecimal(rem, fracDigits);
    }

    private static BigDecimal parseSignedSeparate(byte[] data, int off, int intDigits, int fracDigits) {
        int total = intDigits + fracDigits;
        char c = (char) (data[off] & 0xFF);
        if (c == '+' || c == '-') {
            boolean neg = (c == '-');
            BigInteger un = BigInteger.ZERO;
            for (int i = 0; i < total; i++) {
                char d = (char) (data[off + 1 + i] & 0xFF);
                int dv = (d >= '0' && d <= '9') ? (d - '0') : 0;
                un = un.multiply(BigInteger.TEN).add(BigInteger.valueOf(dv));
            }
            BigDecimal bd = new BigDecimal(un, fracDigits);
            return neg ? bd.negate() : bd;
        }
        if (c >= '0' && c <= '9') {
            BigInteger un = BigInteger.ZERO;
            for (int i = 0; i < total + 1; i++) {
                char d = (char) (data[off + i] & 0xFF);
                int dv = (d >= '0' && d <= '9') ? (d - '0') : 0;
                un = un.multiply(BigInteger.TEN).add(BigInteger.valueOf(dv));
            }
            return new BigDecimal(un, fracDigits);
        }
        BigInteger un = BigInteger.ZERO;
        for (int i = 0; i < total; i++) {
            char d = (char) (data[off + 1 + i] & 0xFF);
            int dv = (d >= '0' && d <= '9') ? (d - '0') : 0;
            un = un.multiply(BigInteger.TEN).add(BigInteger.valueOf(dv));
        }
        return new BigDecimal(un, fracDigits);
    }

    private static int parseUnsigned(byte[] data, int off, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            char d = (char) (data[off + i] & 0xFF);
            int dv = (d >= '0' && d <= '9') ? (d - '0') : 0;
            v = v * 10 + dv;
        }
        return v;
    }

    private static byte[] formatSignedSeparate(BigDecimal v, int intDigits, int fracDigits) {
        BigDecimal t = v.setScale(fracDigits, RoundingMode.DOWN);
        BigInteger unscaled = t.movePointRight(fracDigits).toBigIntegerExact();
        int total = intDigits + fracDigits;
        BigInteger mod = BigInteger.TEN.pow(total);
        BigInteger rem = unscaled.remainder(mod);
        char sign = rem.signum() < 0 ? '-' : '+';
        String digits = rem.abs().toString();
        while (digits.length() < total) {
            digits = "0" + digits;
        }
        byte[] out = new byte[1 + total];
        out[0] = (byte) sign;
        for (int i = 0; i < total; i++) {
            out[1 + i] = (byte) digits.charAt(i);
        }
        return out;
    }
}
