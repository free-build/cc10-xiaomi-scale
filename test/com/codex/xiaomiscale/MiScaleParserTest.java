package com.codex.xiaomiscale;

public final class MiScaleParserTest {
    public static void main(String[] args) {
        byte[] sample = new byte[]{0x62, (byte) 0xAC, 0x49, (byte) 0xE0, 0x07,
                0x0C, 0x14, 0x0D, 0x1C, 0x04};
        MiScaleParser.Result result = MiScaleParser.parse(sample);
        require(result != null, "sample should parse");
        require(Math.abs(result.weightKg - 94.30f) < 0.001f, "kg conversion");
        require(Math.abs(result.displayWeight - 94.30f) < 0.001f, "kg display");
        require("kg".equals(result.sourceUnit), "kg unit");
        require(result.stable, "stable bit");
        require(!result.removed, "removed bit");
        require("2016-12-20 13:28:04".equals(result.measurementTime), "timestamp");
        require(result.measurementTimeMillis > 0, "timestamp millis");
        require(MiScaleParser.parse(new byte[9]) == null, "invalid length");

        byte[] jinPacket = new byte[]{0x12, (byte) 0xC2, 0x10, (byte) 0xE9, 0x07,
                0x0C, 0x17, 0x07, 0x1F, 0x05};
        MiScaleParser.Result jin = MiScaleParser.parse(jinPacket);
        require(jin != null, "jin packet should parse");
        require("斤".equals(jin.sourceUnit), "jin unit");
        require(Math.abs(jin.displayWeight - 42.90f) < 0.001f, "jin display");
        require(Math.abs(jin.weightKg - 21.45f) < 0.001f, "jin normalized kg");

        byte[] lbPacket = new byte[]{0x01, (byte) 0x98, 0x3A, 0, 0, 0, 0, 0, 0, 0};
        MiScaleParser.Result lb = MiScaleParser.parse(lbPacket);
        require(lb != null, "lb packet should parse");
        require("lb".equals(lb.sourceUnit), "lb unit");
        require(Math.abs(lb.displayWeight - 150.0f) < 0.001f, "lb display");
        require(Math.abs(lb.weightKg - 68.03886f) < 0.001f, "lb normalized kg");
        System.out.println("MiScaleParser tests passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
