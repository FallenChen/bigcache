package com.ctriposs.quickcache.utils;

public class ByteUtil {

	public static byte[] toBytes(int n) {
		byte[] bytes = new byte[4];
		bytes[3] = (byte) (n & 0xff);
		bytes[2] = (byte) (n >> 8 & 0xff);
		bytes[1] = (byte) (n >> 16 & 0xff);
		bytes[0] = (byte) (n >> 24 & 0xff);
		return bytes;
	}

	public static byte[] toBytes(long n) {

		byte[] bytes = new byte[8];
		bytes[7] = (byte) (n & 0xff);
		bytes[6] = (byte) (n >> 8 & 0xff);
		bytes[5] = (byte) (n >> 16 & 0xff);
		bytes[4] = (byte) (n >> 24 & 0xff);
		bytes[3] = (byte) (n >> 32 & 0xff);
		bytes[2] = (byte) (n >> 40 & 0xff);
		bytes[1] = (byte) (n >> 48 & 0xff);
		bytes[0] = (byte) (n >> 56 & 0xff);
		return bytes;

	}

	public static byte[] ToBytes(short n) {
		byte[] bytes = new byte[2];
		bytes[1] = (byte) (n & 0xff);
		bytes[0] = (byte) ((n >> 8) & 0xff);
		return bytes;
	}

	public static short ToShort(byte[] bytes) {
		return (short) (bytes[1] & 0xff | (bytes[0] & 0xff) << 8);
	}

	public static int ToInt(byte bytes[]) {
		return bytes[3] & 0xff | (bytes[2] & 0xff) << 8
				| (bytes[1] & 0xff) << 16 | (bytes[0] & 0xff) << 24;
	}

	public static long ToLong(byte[] bytes) {
		return ((((long) bytes[0] & 0xff) << 56)
				| (((long) bytes[1] & 0xff) << 48)
				| (((long) bytes[2] & 0xff) << 40)
				| (((long) bytes[3] & 0xff) << 32)
				| (((long) bytes[4] & 0xff) << 24)
				| (((long) bytes[5] & 0xff) << 16)
				| (((long) bytes[6] & 0xff) << 8) | (((long) bytes[7] & 0xff) << 0));
	}

}