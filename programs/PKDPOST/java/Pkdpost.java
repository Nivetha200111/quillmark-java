public class Pkdpost {
    private java.util.List<byte[]> inRecs = new java.util.ArrayList<>();
    private int inIdx = 0;
    private boolean eof = false;
    private java.io.OutputStream out;
    private byte[] curAcct = new byte[10];
    private java.math.BigInteger curBalCents = java.math.BigInteger.ZERO;
    private java.math.BigInteger curRateInt = java.math.BigInteger.ZERO;
    private java.math.BigInteger curDays = java.math.BigInteger.ZERO;
    private java.math.BigInteger curAdjCents = java.math.BigInteger.ZERO;
    private java.math.BigInteger curLimitCents = java.math.BigInteger.ZERO;
    private java.math.BigInteger curItems = java.math.BigInteger.ZERO;
    private java.math.BigInteger wsIntCents = java.math.BigInteger.ZERO;
    private java.math.BigInteger wsNewBalCents = java.math.BigInteger.ZERO;
    private int wsReadCnt = 0;

    public static void main(String[] args) throws Exception {
        Pkdpost p = new Pkdpost();
        p.p0000Main();
    }

    private void p0000Main() throws Exception {
        String inPath = System.getenv("DD_PKIN");
        String outPath = System.getenv("DD_PKOUT");
        byte[] all = new byte[0];
        if (inPath != null && !inPath.isEmpty()) {
            try {
                all = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(inPath));
            } catch (java.nio.file.NoSuchFileException e) {
                all = new byte[0];
            }
        }
        inRecs.clear();
        for (int i = 0; i + 33 <= all.length; i += 33) {
            byte[] r = new byte[33];
            System.arraycopy(all, i, r, 0, 33);
            inRecs.add(r);
        }
        inIdx = 0;
        eof = false;
        wsReadCnt = 0;
        wsIntCents = java.math.BigInteger.ZERO;
        wsNewBalCents = java.math.BigInteger.ZERO;
        if (outPath != null && !outPath.isEmpty()) {
            out = java.nio.file.Files.newOutputStream(java.nio.file.Path.of(outPath),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
        } else {
            out = new java.io.ByteArrayOutputStream();
        }
        p8000Read();
        while (!eof) {
            p2000Post();
        }
        out.close();
        String cnt = String.format("%07d", wsReadCnt % 10000000);
        System.out.print("PKDPOST READ=" + cnt + "\n");
        System.out.flush();
    }

    private void p2000Post() throws Exception {
        wsReadCnt = (wsReadCnt + 1) % 10000000;
        java.math.BigInteger n = curBalCents.multiply(curRateInt).multiply(curDays);
        java.math.BigDecimal nBd = new java.math.BigDecimal(n);
        java.math.BigDecimal denom = new java.math.BigDecimal(3650000);
        java.math.BigInteger roundedCents = nBd.divide(denom, 0, java.math.RoundingMode.HALF_UP).toBigIntegerExact();
        wsIntCents = truncMod(roundedCents, 9);
        java.math.BigDecimal sumBd = new java.math.BigDecimal(curBalCents).add(new java.math.BigDecimal(wsIntCents)).add(new java.math.BigDecimal(curAdjCents));
        java.math.BigInteger sumInt = sumBd.toBigIntegerExact();
        wsNewBalCents = truncMod(sumInt, 11);
        java.math.BigInteger poIntCents = wsIntCents;
        java.math.BigInteger poNewBalCents = wsNewBalCents;
        java.math.BigDecimal headBd = new java.math.BigDecimal(curLimitCents).subtract(new java.math.BigDecimal(wsNewBalCents));
        java.math.BigInteger headExact = headBd.toBigIntegerExact();
        java.math.BigInteger poHeadCents = truncMod(headExact, 9);
        java.math.BigInteger unitsExact = curItems.multiply(java.math.BigInteger.valueOf(1000));
        java.math.BigInteger poUnits = truncMod(unitsExact, 9);
        java.math.BigInteger revExact = curAdjCents.negate();
        java.math.BigInteger poRevCents = truncMod(revExact, 7);
        byte[] rec = new byte[42];
        System.arraycopy(curAcct, 0, rec, 0, 10);
        encodePacked(poIntCents, rec, 10, 5, 9);
        encodePacked(poNewBalCents, rec, 15, 6, 11);
        encodeHeadroom(poHeadCents, rec, 21);
        encodeComp(poUnits, rec, 31, 4);
        encodeZonedCents(poRevCents, rec, 35, 7);
        out.write(rec);
        p8000Read();
    }

    private void p8000Read() {
        if (inIdx >= inRecs.size()) {
            eof = true;
            return;
        }
        byte[] r = inRecs.get(inIdx++);
        System.arraycopy(r, 0, curAcct, 0, 10);
        curBalCents = parsePackedDigits(r, 10, 6, 11, true);
        curRateInt = parsePackedDigits(r, 16, 3, 5, false);
        curDays = parseComp(r, 19, 2, true);
        curAdjCents = parseZoned(r, 21, 7);
        curLimitCents = parseLimit(r, 28);
        curItems = parseComp5(r, 31, 2, true);
    }

    private static java.math.BigInteger truncMod(java.math.BigInteger v, int digits) {
        if (v.signum() == 0) return java.math.BigInteger.ZERO;
        java.math.BigInteger mod = java.math.BigInteger.TEN.pow(digits);
        java.math.BigInteger m = v.abs().mod(mod);
        if (m.signum() == 0) return java.math.BigInteger.ZERO;
        return v.signum() < 0 ? m.negate() : m;
    }

    private static java.math.BigInteger parsePackedDigits(byte[] d, int off, int len, int digits, boolean signed) {
        java.math.BigInteger v = java.math.BigInteger.ZERO;
        for (int k = 0; k < digits; k++) {
            int b = d[off + k / 2] & 0xFF;
            int nib = (k % 2 == 0) ? (b >> 4) & 0xF : b & 0xF;
            v = v.multiply(java.math.BigInteger.TEN).add(java.math.BigInteger.valueOf(nib));
        }
        int signNib = d[off + len - 1] & 0xF;
        boolean neg = false;
        if (signed) neg = (signNib == 0x0D);
        if (neg && v.signum() != 0) v = v.negate();
        return v;
    }

    private static void encodePacked(java.math.BigInteger v, byte[] out, int off, int len, int digits) {
        boolean neg = v.signum() < 0;
        java.math.BigInteger mag = v.abs();
        int[] dig = new int[digits];
        for (int i = digits - 1; i >= 0; i--) {
            java.math.BigInteger[] qr = mag.divideAndRemainder(java.math.BigInteger.TEN);
            dig[i] = qr[1].intValue();
            mag = qr[0];
        }
        int sign = neg ? 0x0D : 0x0C;
        for (int i = 0; i < len; i++) {
            int hiIdx = i * 2;
            int loIdx = i * 2 + 1;
            int hi = hiIdx < digits ? dig[hiIdx] : 0;
            int lo;
            if (loIdx < digits) lo = dig[loIdx];
            else if (loIdx == digits) lo = sign;
            else lo = 0;
            out[off + i] = (byte) ((hi << 4) | lo);
        }
    }

    private static java.math.BigInteger parseComp(byte[] d, int off, int len, boolean signed) {
        long u = 0;
        for (int i = 0; i < len; i++) u = (u << 8) | (d[off + i] & 0xFF);
        if (signed) {
            long bits = (long) len * 8;
            long signBit = 1L << (bits - 1);
            if ((u & signBit) != 0) u -= (1L << bits);
        }
        return java.math.BigInteger.valueOf(u);
    }

    private static java.math.BigInteger parseComp5(byte[] d, int off, int len, boolean signed) {
        long u = 0;
        for (int i = 0; i < len; i++) u |= ((long) (d[off + i] & 0xFF)) << (8 * i);
        if (signed) {
            long bits = (long) len * 8;
            long signBit = 1L << (bits - 1);
            if ((u & signBit) != 0) u -= (1L << bits);
        }
        return java.math.BigInteger.valueOf(u);
    }

    private static void encodeComp(java.math.BigInteger v, byte[] out, int off, int len) {
        long lv = v.longValue();
        for (int i = 0; i < len; i++) out[off + i] = (byte) ((lv >> (8 * (len - 1 - i))) & 0xFF);
    }

    private static java.math.BigInteger parseZoned(byte[] d, int off, int len) {
        java.math.BigInteger v = java.math.BigInteger.ZERO;
        for (int i = 0; i < len - 1; i++) {
            int c = d[off + i] & 0xFF;
            int digit = c - 0x30;
            if (digit < 0 || digit > 9) digit = 0;
            v = v.multiply(java.math.BigInteger.TEN).add(java.math.BigInteger.valueOf(digit));
        }
        int last = d[off + len - 1] & 0xFF;
        int digit;
        boolean neg = false;
        if (last >= 0x30 && last <= 0x39) { digit = last - 0x30; neg = false; }
        else if (last >= 0x70 && last <= 0x79) { digit = last - 0x70; neg = true; }
        else if (last >= 0x50 && last <= 0x59) { digit = last - 0x50; neg = true; }
        else { digit = 0; neg = false; }
        v = v.multiply(java.math.BigInteger.TEN).add(java.math.BigInteger.valueOf(digit));
        if (neg && v.signum() != 0) v = v.negate();
        return v;
    }

    private static java.math.BigInteger parseLimit(byte[] d, int off) {
        int v = 0;
        for (int i = 0; i < 3; i++) {
            int c = d[off + i] & 0xFF;
            int digit = c - 0x30;
            if (digit < 0 || digit > 9) digit = 0;
            v = v * 10 + digit;
        }
        return java.math.BigInteger.valueOf(v).multiply(java.math.BigInteger.valueOf(10000));
    }

    private static void encodeZonedCents(java.math.BigInteger v, byte[] out, int off, int len) {
        boolean neg = v.signum() < 0;
        java.math.BigInteger mag = v.abs();
        int[] dig = new int[len];
        for (int i = len - 1; i >= 0; i--) {
            java.math.BigInteger[] qr = mag.divideAndRemainder(java.math.BigInteger.TEN);
            dig[i] = qr[1].intValue();
            mag = qr[0];
        }
        for (int i = 0; i < len - 1; i++) out[off + i] = (byte) (0x30 + dig[i]);
        out[off + len - 1] = (byte) ((neg ? 0x70 : 0x30) + dig[len - 1]);
    }

    private static void encodeHeadroom(java.math.BigInteger v, byte[] out, int off) {
        int digits = 9;
        boolean neg = v.signum() < 0;
        java.math.BigInteger mag = v.abs();
        int[] dig = new int[digits];
        for (int i = digits - 1; i >= 0; i--) {
            java.math.BigInteger[] qr = mag.divideAndRemainder(java.math.BigInteger.TEN);
            dig[i] = qr[1].intValue();
            mag = qr[0];
        }
        for (int i = 0; i < digits; i++) out[off + i] = (byte) (0x30 + dig[i]);
        out[off + digits] = (byte) (neg ? '-' : '+');
    }
}
