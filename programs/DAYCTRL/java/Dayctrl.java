import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Dayctrl {
    private static String wsDayStatus = "00";
    private static String wsCtlStatus = "00";
    private static boolean endOfAccounts = false;
    private static long wsInterestCents = 0;
    private static long wsCtlTotalCents = 0;
    private static long wsReadCnt = 0;
    private static long wsLostCnt = 0;
    private static byte[] dayBytes = new byte[0];
    private static int dayPos = 0;
    private static final byte[] curAcctId = new byte[10];
    private static long curPrincipalCents = 0;
    private static int curRateDigits = 0;
    private static int curDays = 0;
    private static char ctlFlag = '-';
    private static OutputStream ctlOut = null;

    public static void main(String[] args) throws Exception {
        p0000Main();
    }

    private static void p0000Main() throws Exception {
        p1000Init();
        while (!endOfAccounts) {
            p2000Process();
        }
        p9000Term();
    }

    private static void p1000Init() throws Exception {
        String dayPath = System.getenv("DD_DAYIN");
        if (dayPath == null || dayPath.isEmpty()) {
            wsDayStatus = "35";
            System.out.println("DAYCTRL OPEN DAYIN FAILED " + wsDayStatus);
            System.exit(16);
            return;
        }
        try {
            Path p = Paths.get(dayPath);
            dayBytes = Files.readAllBytes(p);
            wsDayStatus = "00";
        } catch (Exception e) {
            wsDayStatus = "35";
            System.out.println("DAYCTRL OPEN DAYIN FAILED " + wsDayStatus);
            System.exit(16);
            return;
        }
        dayPos = 0;
        String ctlPath = System.getenv("DD_CTLOUT");
        if (ctlPath == null || ctlPath.isEmpty()) {
            wsCtlStatus = "35";
            System.out.println("DAYCTRL OPEN CTLOUT FAILED " + wsCtlStatus);
            System.exit(16);
            return;
        }
        try {
            Path p = Paths.get(ctlPath);
            ctlOut = Files.newOutputStream(p, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
            wsCtlStatus = "00";
        } catch (Exception e) {
            wsCtlStatus = "35";
            System.out.println("DAYCTRL OPEN CTLOUT FAILED " + wsCtlStatus);
            System.exit(16);
            return;
        }
        p8000Read();
    }

    private static void p2000Process() throws Exception {
        wsReadCnt = (wsReadCnt + 1) % 10000000L;
        p3000Accrue();
        p5000WriteResult();
        p8000Read();
    }

    private static void p3000Accrue() {
        BigInteger n = BigInteger.valueOf(curPrincipalCents)
                .multiply(BigInteger.valueOf((long) curRateDigits))
                .multiply(BigInteger.valueOf((long) curDays));
        long interest;
        if (n.signum() == 0) {
            interest = 0;
        } else {
            int sign = n.signum();
            BigInteger abs = n.abs();
            BigInteger divisor = BigInteger.valueOf(3650000L);
            BigInteger[] qr = abs.divideAndRemainder(divisor);
            BigInteger q = qr[0];
            BigInteger r = qr[1];
            if (r.shiftLeft(1).compareTo(divisor) >= 0) {
                q = q.add(BigInteger.ONE);
            }
            long ql = q.longValue();
            interest = sign < 0 ? -ql : ql;
        }
        if (interest != 0) {
            long a = interest < 0 ? -interest : interest;
            long m = a % 1000000000L;
            if (m == 0) {
                interest = 0;
            } else {
                interest = interest < 0 ? -m : m;
            }
        }
        wsInterestCents = interest;
        long sum = wsCtlTotalCents + wsInterestCents;
        if (sum != 0) {
            long a = sum < 0 ? -sum : sum;
            long m = a % 10000000000000L;
            if (m == 0) {
                sum = 0;
            } else {
                sum = sum < 0 ? -m : m;
            }
        }
        wsCtlTotalCents = sum;
        if (wsInterestCents == 0 && curPrincipalCents != 0 && curRateDigits != 0 && curDays != 0) {
            ctlFlag = 'Z';
            wsLostCnt = (wsLostCnt + 1) % 10000000L;
        } else {
            ctlFlag = '-';
        }
    }

    private static void p5000WriteResult() throws Exception {
        byte[] rec = new byte[35];
        System.arraycopy(curAcctId, 0, rec, 0, 10);
        formatSignedSeparate(wsInterestCents, 9, rec, 10);
        formatSignedSeparate(wsCtlTotalCents, 13, rec, 20);
        rec[34] = (byte) ctlFlag;
        ctlOut.write(rec);
    }

    private static void p8000Read() {
        if (dayPos + 30 > dayBytes.length) {
            endOfAccounts = true;
            return;
        }
        int o = dayPos;
        System.arraycopy(dayBytes, o, curAcctId, 0, 10);
        int signByte = dayBytes[o + 10] & 0xFF;
        boolean neg = (signByte == '-');
        long v = 0;
        for (int i = 0; i < 11; i++) {
            int b = dayBytes[o + 11 + i] & 0xFF;
            v = v * 10 + (b - '0');
        }
        curPrincipalCents = neg ? -v : v;
        int r = 0;
        for (int i = 0; i < 5; i++) {
            int b = dayBytes[o + 22 + i] & 0xFF;
            r = r * 10 + (b - '0');
        }
        curRateDigits = r;
        int d = 0;
        for (int i = 0; i < 3; i++) {
            int b = dayBytes[o + 27 + i] & 0xFF;
            d = d * 10 + (b - '0');
        }
        curDays = d;
        dayPos += 30;
    }

    private static void p9000Term() throws Exception {
        if (ctlOut != null) {
            try { ctlOut.close(); } catch (Exception ignored) {}
            ctlOut = null;
        }
        String edit = formatCtlEdit(wsCtlTotalCents);
        String line = "DAYCTRL ACCOUNTS=" + formatComp7(wsReadCnt)
                + " LOST=" + formatComp7(wsLostCnt)
                + " CONTROL=" + edit;
        System.out.println(line);
    }

    private static String formatComp7(long v) {
        long m = v % 10000000L;
        if (m < 0) m += 10000000L;
        String s = Long.toString(m);
        while (s.length() < 7) s = "0" + s;
        return s;
    }

    private static void formatSignedSeparate(long cents, int digits, byte[] dest, int off) {
        boolean neg = cents < 0;
        long a = neg ? -cents : cents;
        String s = Long.toString(a);
        while (s.length() < digits) s = "0" + s;
        if (s.length() > digits) s = s.substring(s.length() - digits);
        dest[off] = (byte) (neg ? '-' : '+');
        for (int i = 0; i < digits; i++) {
            dest[off + 1 + i] = (byte) s.charAt(i);
        }
    }

    private static String formatCtlEdit(long cents) {
        boolean neg = cents < 0;
        long a = neg ? -cents : cents;
        long intPart = a / 100L;
        int frac = (int) (a % 100L);
        String intStr = Long.toString(intPart);
        String fracStr = frac < 10 ? "0" + frac : Integer.toString(frac);
        String intField;
        if (!neg) {
            int spaces = 12 - intStr.length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < spaces; i++) sb.append(' ');
            sb.append(intStr);
            intField = sb.toString();
        } else {
            int spaces = 12 - (intStr.length() + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < spaces; i++) sb.append(' ');
            sb.append('-');
            sb.append(intStr);
            intField = sb.toString();
        }
        return intField + "." + fracStr;
    }
}
