import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Acctmtch {
    private InputStream mstIn;
    private InputStream txnIn;
    private OutputStream newOut;
    private OutputStream excOut;

    private byte[] wsMstKey = new byte[8];
    private byte[] wsTxnKey = new byte[8];
    private BigDecimal wsBalance = BigDecimal.ZERO.setScale(2);
    private int wsCount = 0;
    private long wsExcCnt = 0;
    private long wsNewCnt = 0;

    private byte[] msAcctId = new byte[8];
    private byte[] msBalanceBytes = new byte[12];
    private byte[] msStatus = new byte[4];

    private byte[] txAcctId = new byte[8];
    private byte[] txAmountBytes = new byte[10];
    private byte txType = (byte) ' ';

    private byte[] exAcctId = new byte[8];
    private byte[] exAmount = new byte[10];
    private byte[] exReason = new byte[12];

    private static final int[] EBCDIC_ORDER = new int[256];
    static {
        Charset cp037 = Charset.forName("Cp037");
        for (int i = 0; i < 256; i++) {
            String s = new String(new byte[]{(byte) i}, StandardCharsets.ISO_8859_1);
            byte[] eb = s.getBytes(cp037);
            if (eb.length == 1) EBCDIC_ORDER[i] = eb[0] & 0xFF;
            else EBCDIC_ORDER[i] = i;
        }
    }

    public static void main(String[] args) throws Exception {
        Acctmtch p = new Acctmtch();
        p.m0000Main();
    }

    // 0000-MAIN
    private void m0000Main() throws Exception {
        String mstPath = System.getenv("DD_MSTIN");
        String txnPath = System.getenv("DD_TXNIN");
        String newPath = System.getenv("DD_NEWMST");
        String excPath = System.getenv("DD_EXCOUT");
        mstIn = openInput(mstPath);
        txnIn = openInput(txnPath);
        newOut = openOutput(newPath);
        excOut = openOutput(excPath);
        m8100ReadMst();
        m8200ReadTxn();
        while (!(isHigh(wsMstKey) && isHigh(wsTxnKey))) {
            m2000Match();
        }
        mstIn.close();
        txnIn.close();
        newOut.close();
        excOut.close();
        String line = "ACCTMTCH NEW=" + String.format("%07d", wsNewCnt) + " EXC=" + String.format("%07d", wsExcCnt);
        System.out.write((line + "\n").getBytes(StandardCharsets.ISO_8859_1));
        System.out.flush();
        if (wsExcCnt > 0) {
            System.exit(4);
        }
    }

    // 2000-MATCH
    private void m2000Match() throws Exception {
        int cmp = compareKeys(wsTxnKey, wsMstKey);
        if (cmp < 0) {
            System.arraycopy(txAcctId, 0, exAcctId, 0, 8);
            System.arraycopy(txAmountBytes, 0, exAmount, 0, 10);
            setReason("NO ACCOUNT");
            m7000WriteExc();
            m8200ReadTxn();
        } else if (cmp == 0) {
            m3000Apply();
            m8200ReadTxn();
        } else {
            m4000WriteMaster();
            m8100ReadMst();
        }
    }

    // 3000-APPLY
    private void m3000Apply() throws Exception {
        if (isFrozen()) {
            System.arraycopy(txAcctId, 0, exAcctId, 0, 8);
            System.arraycopy(txAmountBytes, 0, exAmount, 0, 10);
            setReason("FROZEN");
            m7000WriteExc();
        } else {
            if (txType == (byte) 'C') {
                BigDecimal txAmt = parseSignedSeparate(txAmountBytes, 2);
                wsBalance = truncToPic(wsBalance.add(txAmt), 9, 2);
                wsCount = (wsCount + 1) % 10000;
            } else if (txType == (byte) 'D') {
                BigDecimal txAmt = parseSignedSeparate(txAmountBytes, 2);
                wsBalance = truncToPic(wsBalance.subtract(txAmt), 9, 2);
                wsCount = (wsCount + 1) % 10000;
            } else {
                System.arraycopy(txAcctId, 0, exAcctId, 0, 8);
                System.arraycopy(txAmountBytes, 0, exAmount, 0, 10);
                setReason("BAD TYPE");
                m7000WriteExc();
            }
        }
    }

    // 4000-WRITE-MASTER
    private void m4000WriteMaster() throws Exception {
        byte[] rec = new byte[28];
        System.arraycopy(msAcctId, 0, rec, 0, 8);
        byte[] bal = formatSignedSeparate(wsBalance, 12, 2);
        System.arraycopy(bal, 0, rec, 8, 12);
        System.arraycopy(msStatus, 0, rec, 20, 4);
        String c = String.format("%04d", wsCount);
        byte[] cb = c.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(cb, 0, rec, 24, 4);
        newOut.write(rec);
        wsNewCnt = (wsNewCnt + 1) % 10000000L;
    }

    // 7000-WRITE-EXC
    private void m7000WriteExc() throws Exception {
        byte[] rec = new byte[30];
        System.arraycopy(exAcctId, 0, rec, 0, 8);
        System.arraycopy(exAmount, 0, rec, 8, 10);
        System.arraycopy(exReason, 0, rec, 18, 12);
        excOut.write(rec);
        wsExcCnt = (wsExcCnt + 1) % 10000000L;
    }

    // 8100-READ-MST
    private void m8100ReadMst() throws Exception {
        byte[] rec = readFull(mstIn, 24);
        if (rec == null) {
            Arrays.fill(wsMstKey, (byte) 0xFF);
        } else {
            System.arraycopy(rec, 0, msAcctId, 0, 8);
            System.arraycopy(rec, 8, msBalanceBytes, 0, 12);
            System.arraycopy(rec, 20, msStatus, 0, 4);
            System.arraycopy(msAcctId, 0, wsMstKey, 0, 8);
            wsBalance = truncToPic(parseSignedSeparate(msBalanceBytes, 2), 9, 2);
            wsCount = 0;
        }
    }

    // 8200-READ-TXN
    private void m8200ReadTxn() throws Exception {
        byte[] rec = readFull(txnIn, 19);
        if (rec == null) {
            Arrays.fill(wsTxnKey, (byte) 0xFF);
        } else {
            System.arraycopy(rec, 0, txAcctId, 0, 8);
            System.arraycopy(rec, 8, txAmountBytes, 0, 10);
            txType = rec[18];
            System.arraycopy(txAcctId, 0, wsTxnKey, 0, 8);
        }
    }

    private void setReason(String s) {
        Arrays.fill(exReason, (byte) ' ');
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(b, 0, exReason, 0, Math.min(b.length, 12));
    }

    private boolean isFrozen() {
        return msStatus[0] == (byte) 'F' && msStatus[1] == (byte) 'R' && msStatus[2] == (byte) 'Z' && msStatus[3] == (byte) 'N';
    }

    private static boolean isHigh(byte[] k) {
        for (byte b : k) if (b != (byte) 0xFF) return false;
        return true;
    }

    private static int compareKeys(byte[] a, byte[] b) {
        boolean ah = isHigh(a);
        boolean bh = isHigh(b);
        if (ah && bh) return 0;
        if (ah) return 1;
        if (bh) return -1;
        boolean eb = "EBCDIC".equals(System.getenv("DIOCLE_COLLATION"));
        for (int i = 0; i < a.length; i++) {
            int av = a[i] & 0xFF;
            int bv = b[i] & 0xFF;
            if (eb) {
                av = EBCDIC_ORDER[av];
                bv = EBCDIC_ORDER[bv];
            }
            if (av != bv) return av < bv ? -1 : 1;
        }
        return 0;
    }

    private static BigDecimal parseSignedSeparate(byte[] b, int frac) {
        char c = (char) (b[0] & 0xFF);
        boolean neg = (c == '-');
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < b.length; i++) {
            char d = (char) (b[i] & 0xFF);
            if (d >= '0' && d <= '9') sb.append(d);
            else if (d == ' ') sb.append('0');
            else sb.append(d);
        }
        String digits = sb.length() == 0 ? "0" : sb.toString();
        BigInteger bi;
        try {
            bi = new BigInteger(digits);
        } catch (NumberFormatException e) {
            bi = BigInteger.ZERO;
        }
        BigDecimal v = new BigDecimal(bi).movePointLeft(frac).setScale(frac);
        if (neg) v = v.negate();
        return v;
    }

    private static BigDecimal truncToPic(BigDecimal v, int intDigits, int fracDigits) {
        int total = intDigits + fracDigits;
        BigInteger mod = BigInteger.TEN.pow(total);
        BigInteger scaled = v.movePointRight(fracDigits).toBigIntegerExact();
        boolean neg = scaled.signum() < 0;
        BigInteger a = scaled.abs().mod(mod);
        if (a.equals(BigInteger.ZERO)) return BigDecimal.ZERO.setScale(fracDigits);
        BigInteger s = neg ? a.negate() : a;
        return new BigDecimal(s).movePointLeft(fracDigits).setScale(fracDigits);
    }

    private static byte[] formatSignedSeparate(BigDecimal v, int totalLen, int fracDigits) {
        BigDecimal sc = v.setScale(fracDigits);
        BigInteger scaled = sc.movePointRight(fracDigits).toBigIntegerExact();
        boolean neg = scaled.signum() < 0;
        String s = scaled.abs().toString();
        int digits = totalLen - 1;
        if (s.length() > digits) s = s.substring(s.length() - digits);
        else if (s.length() < digits) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digits - s.length(); i++) sb.append('0');
            sb.append(s);
            s = sb.toString();
        }
        byte[] out = new byte[totalLen];
        out[0] = (byte) (neg ? '-' : '+');
        byte[] db = s.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(db, 0, out, 1, digits);
        return out;
    }

    private static byte[] readFull(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r == -1) {
                if (off == 0) return null;
                break;
            }
            off += r;
        }
        if (off < len) return null;
        return buf;
    }

    private static InputStream openInput(String path) throws IOException {
        if (path == null) return new ByteArrayInputStream(new byte[0]);
        try {
            return new FileInputStream(path);
        } catch (FileNotFoundException e) {
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    private static OutputStream openOutput(String path) throws IOException {
        if (path == null) throw new FileNotFoundException("missing DD path");
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        return new FileOutputStream(f, false);
    }
}
