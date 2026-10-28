import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class Intaccr {
    private static final int ACCT_REC_LEN = 31;
    private static final int OUT_REC_LEN = 42;
    private static final BigDecimal C365 = BigDecimal.valueOf(365);
    private static final BigDecimal FEE_FACTOR = new BigDecimal("0.0015");
    private String wsAcctStatus = "00";
    private String wsOutStatus = "00";
    private boolean endOfAccounts = false;
    private BigDecimal wsPenaltyRate = new BigDecimal("0.0500");
    private BigDecimal wsFeeFloor = new BigDecimal("1000.00");
    private BigDecimal wsFeeCap = new BigDecimal("25.00");
    private BigDecimal wsInterest = BigDecimal.ZERO.setScale(2);
    private BigDecimal wsFee = BigDecimal.ZERO.setScale(2);
    private BigDecimal wsNewBal = BigDecimal.ZERO.setScale(2);
    private int wsReadCnt = 0;
    private int wsWriteCnt = 0;
    private int returnCode = 0;
    private byte[] acctIdBytes = new byte[10];
    private BigDecimal acctBalance = BigDecimal.ZERO.setScale(2);
    private BigDecimal acctRate = BigDecimal.ZERO.setScale(4);
    private int acctDays = 0;
    private byte acctTier = (byte) ' ';
    private byte[] acctFileBytes = new byte[0];
    private int acctRecCount = 0;
    private int acctRecIndex = 0;
    private OutputStream outStream = null;
    private byte[] outAcctId = new byte[10];
    private BigDecimal outInterestVal;
    private BigDecimal outFeeVal;
    private BigDecimal outNewBalVal;
    private String outStatusStr = "OK";

    public static void main(String[] args) throws Exception {
        Intaccr p = new Intaccr();
        int rc = p.runProgram();
        if (rc != 0) System.exit(rc);
    }

    private int runProgram() throws Exception {
        p0000Main();
        return returnCode;
    }

    private void p0000Main() throws Exception {
        p1000Init();
        if (returnCode != 0) return;
        while (!endOfAccounts) {
            p2000Process();
        }
        p9000Term();
    }

    private void p1000Init() throws Exception {
        String acctPath = System.getenv("DD_ACCTIN");
        if (acctPath == null || acctPath.isEmpty()) {
            wsAcctStatus = "35";
            System.out.println("INTACCR OPEN ACCTIN FAILED " + wsAcctStatus);
            returnCode = 16;
            return;
        }
        try {
            acctFileBytes = Files.readAllBytes(Paths.get(acctPath));
            wsAcctStatus = "00";
        } catch (NoSuchFileException e) {
            wsAcctStatus = "35";
            System.out.println("INTACCR OPEN ACCTIN FAILED " + wsAcctStatus);
            returnCode = 16;
            return;
        } catch (java.io.IOException e) {
            wsAcctStatus = "30";
            System.out.println("INTACCR OPEN ACCTIN FAILED " + wsAcctStatus);
            returnCode = 16;
            return;
        }
        acctRecCount = acctFileBytes.length / ACCT_REC_LEN;
        int rem = acctFileBytes.length % ACCT_REC_LEN;
        if (rem != 0) {
            byte[] padded = new byte[(acctRecCount + 1) * ACCT_REC_LEN];
            System.arraycopy(acctFileBytes, 0, padded, 0, acctFileBytes.length);
            Arrays.fill(padded, acctFileBytes.length, padded.length, (byte) 0x20);
            acctFileBytes = padded;
            acctRecCount = acctRecCount + 1;
        }
        acctRecIndex = 0;
        String outPath = System.getenv("DD_INTOUT");
        if (outPath == null || outPath.isEmpty()) {
            wsOutStatus = "30";
            System.out.println("INTACCR OPEN INTOUT FAILED " + wsOutStatus);
            returnCode = 16;
            return;
        }
        try {
            Path op = Paths.get(outPath);
            outStream = Files.newOutputStream(op, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            wsOutStatus = "00";
        } catch (java.io.IOException e) {
            wsOutStatus = "30";
            System.out.println("INTACCR OPEN INTOUT FAILED " + wsOutStatus);
            returnCode = 16;
            return;
        }
        p8000Read();
    }

    private void p2000Process() throws Exception {
        wsReadCnt = (wsReadCnt + 1) % 10000000;
        p3000CalcInterest();
        p4000CalcFee();
        p5000WriteResult();
        p8000Read();
    }

    private void p3000CalcInterest() {
        int cmp = acctBalance.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            BigDecimal num = acctBalance.multiply(acctRate).multiply(BigDecimal.valueOf(acctDays));
            BigDecimal r = num.divide(C365, 2, RoundingMode.HALF_UP);
            wsInterest = picTruncate(r, 7);
        } else if (cmp < 0) {
            BigDecimal eff = acctRate.add(wsPenaltyRate);
            BigDecimal num = acctBalance.multiply(eff).multiply(BigDecimal.valueOf(acctDays));
            BigDecimal r = num.divide(C365, 2, RoundingMode.HALF_UP);
            wsInterest = picTruncate(r, 7);
        } else {
            wsInterest = BigDecimal.ZERO.setScale(2);
        }
    }

    private void p4000CalcFee() {
        wsFee = BigDecimal.ZERO.setScale(2);
        boolean isPremium = (acctTier == (byte) 'P');
        if (!isPremium && acctBalance.compareTo(wsFeeFloor) < 0) {
            BigDecimal shortfall = wsFeeFloor.subtract(acctBalance);
            BigDecimal exact = shortfall.multiply(FEE_FACTOR);
            BigDecimal trunc = exact.setScale(2, RoundingMode.DOWN);
            BigDecimal stored = picTruncate(trunc, 5);
            wsFee = stored;
            if (wsFee.compareTo(wsFeeCap) > 0) {
                wsFee = wsFeeCap;
            }
        }
    }

    private void p5000WriteResult() throws Exception {
        BigDecimal nbExact = acctBalance.add(wsInterest).subtract(wsFee);
        nbExact = nbExact.setScale(2, RoundingMode.UNNECESSARY);
        wsNewBal = picTruncate(nbExact, 9);
        outAcctId = Arrays.copyOf(acctIdBytes, 10);
        outInterestVal = wsInterest;
        outFeeVal = wsFee;
        outNewBalVal = wsNewBal;
        if (wsNewBal.compareTo(BigDecimal.ZERO) < 0) {
            outStatusStr = "OD";
        } else if (wsFee.compareTo(BigDecimal.ZERO) > 0) {
            outStatusStr = "FE";
        } else {
            outStatusStr = "OK";
        }
        byte[] rec = new byte[OUT_REC_LEN];
        System.arraycopy(outAcctId, 0, rec, 0, 10);
        byte[] ib = formatSignedSeparate(outInterestVal, 7);
        byte[] fb = formatSignedSeparate(outFeeVal, 5);
        byte[] nb = formatSignedSeparate(outNewBalVal, 9);
        System.arraycopy(ib, 0, rec, 10, 10);
        System.arraycopy(fb, 0, rec, 20, 8);
        System.arraycopy(nb, 0, rec, 28, 12);
        rec[40] = (byte) outStatusStr.charAt(0);
        rec[41] = (byte) outStatusStr.charAt(1);
        outStream.write(rec);
        wsWriteCnt = (wsWriteCnt + 1) % 10000000;
    }

    private void p8000Read() {
        if (acctRecIndex >= acctRecCount) {
            endOfAccounts = true;
            return;
        }
        int off = acctRecIndex * ACCT_REC_LEN;
        acctRecIndex++;
        System.arraycopy(acctFileBytes, off, acctIdBytes, 0, 10);
        byte sign = acctFileBytes[off + 10];
        BigDecimal bal;
        if (sign >= '0' && sign <= '9') {
            String s = new String(acctFileBytes, off + 10, 12, StandardCharsets.ISO_8859_1);
            s = s.trim();
            if (s.isEmpty()) s = "0";
            bal = new BigDecimal(s).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
        } else {
            String digs = new String(acctFileBytes, off + 11, 11, StandardCharsets.ISO_8859_1);
            BigDecimal abs;
            String t = digs.trim();
            if (t.isEmpty()) {
                abs = BigDecimal.ZERO.setScale(2);
            } else {
                abs = new BigDecimal(digs).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
            }
            if (sign == '-' ) bal = abs.negate();
            else bal = abs;
        }
        acctBalance = bal;
        String rateStr = new String(acctFileBytes, off + 22, 5, StandardCharsets.ISO_8859_1);
        BigDecimal rate;
        try {
            String rt = rateStr.trim();
            if (rt.isEmpty()) rt = "0";
            rate = new BigDecimal(rt).movePointLeft(4).setScale(4, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException e) {
            rate = BigDecimal.ZERO.setScale(4);
        }
        acctRate = rate;
        String daysStr = new String(acctFileBytes, off + 27, 3, StandardCharsets.ISO_8859_1);
        int days;
        try {
            days = Integer.parseInt(daysStr.trim().isEmpty() ? "0" : daysStr.trim());
        } catch (NumberFormatException e) {
            days = 0;
        }
        acctDays = days;
        acctTier = acctFileBytes[off + 30];
    }

    private void p9000Term() throws Exception {
        if (outStream != null) {
            try { outStream.close(); } catch (java.io.IOException e) { }
            outStream = null;
        }
        String rc = String.format("%07d", wsReadCnt);
        String wc = String.format("%07d", wsWriteCnt);
        System.out.println("INTACCR READ=" + rc + " WRITTEN=" + wc);
    }

    private static BigDecimal picTruncate(BigDecimal v, int intDigits) {
        BigDecimal vs = v.setScale(2, RoundingMode.UNNECESSARY);
        BigInteger scaled = vs.movePointRight(2).toBigIntegerExact();
        if (scaled.signum() == 0) return BigDecimal.ZERO.setScale(2);
        BigInteger mod = BigInteger.TEN.pow(intDigits + 2);
        BigInteger a = scaled.abs().mod(mod);
        if (a.signum() == 0) return BigDecimal.ZERO.setScale(2);
        BigInteger r = scaled.signum() < 0 ? a.negate() : a;
        return new BigDecimal(r, 2);
    }

    private static byte[] formatSignedSeparate(BigDecimal v, int intDigits) {
        int total = intDigits + 2;
        byte sign = v.signum() < 0 ? (byte) '-' : (byte) '+';
        BigInteger absScaled = v.abs().movePointRight(2).toBigIntegerExact();
        String ds = absScaled.toString();
        if (ds.length() > total) ds = ds.substring(ds.length() - total);
        else if (ds.length() < total) {
            StringBuilder sb = new StringBuilder(total);
            for (int i = 0; i < total - ds.length(); i++) sb.append('0');
            sb.append(ds);
            ds = sb.toString();
        }
        byte[] out = new byte[total + 1];
        out[0] = sign;
        for (int i = 0; i < total; i++) out[i + 1] = (byte) ds.charAt(i);
        return out;
    }
}
