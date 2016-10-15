package pl.nn44.rchat.server.util;

import com.google.common.base.Strings;
import com.google.common.math.IntMath;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.Random;

public class BigIdGenerator implements Iterator<String> {

    private final static int NUMBER_BASE = 32;
    private final static int BIT_PER_CHAR = IntMath.log2(NUMBER_BASE, RoundingMode.UNNECESSARY);

    private final Random random;
    private final int chars;
    private final int bits;

    private BigIdGenerator(Random random, int chars) {
        this.random = random;
        this.chars = chars;
        this.bits = chars * BIT_PER_CHAR;
    }

    public static BigIdGenerator chars(Random random, int chars) {
        return new BigIdGenerator(random, chars);
    }

    public static BigIdGenerator bits(Random random, int bits) {
        int chars = (bits + BIT_PER_CHAR - 1) / BIT_PER_CHAR;
        return new BigIdGenerator(random, chars);
    }

    public int getChars() {
        return chars;
    }

    public int getBits() {
        return bits;
    }

    @Override
    public boolean hasNext() {
        return true;
    }

    @Override
    public String next() {
        String nextId = new BigInteger(bits, random).toString(NUMBER_BASE);
        return Strings.padStart(nextId, chars, '0');
    }
}
