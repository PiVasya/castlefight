package dev.cf.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * CF Overlay v1: no disk cache, no request on G, no client-side map scan.
 * Both Minestom server and Fabric client use this same protocol source.
 *
 * Binary geometry is zlib-compressed once and split into bounded custom
 * payloads. All received geometry is untrusted: hard limits in the decoder
 * prevent a malformed server packet from allocating arbitrary memory.
 *
 * IMPORTANT: carpet color index means BOTH a team and a lane. Never combine
 * zones by team or assign a lane by nearest route. See CFMarkerPalette.
 */
public final class CFOverlayWire {
    public static final String CHANNEL = "castlefight:overlay_v1";
    public static final int FRAME_MAGIC = 0x43464F31; // CFO1
    public static final int GEOMETRY_MAGIC = 0x43464731; // CFG1
    public static final int VERSION = 1;
    public static final int PART_BYTES = 16_000;
    public static final int MAX_ZONES = 200_000;
    public static final int MAX_PATH_NODES = 100_000;
    public static final int MAX_COMPRESSED = 4_000_000;
    public static final int MAX_RAW = 8_000_000;
    public static final int MAX_FRAMES = 256;
    private static final int HEADER_BYTES = 4+2+4+2+2+4+4;

    public record Cell(int x, int y, int z, int palette) {
        public Cell {
            if (palette < 0 || palette > 15 || y < -64 || y > 319) throw new IllegalArgumentException("Invalid zone cell");
        }
    }
    public record Point(float x, float y, float z) {
        public Point {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("Non-finite path point");
        }
    }
    public record Route(int lane, List<Point> nodes) {
        public Route {
            if (lane < 1 || lane > 4 || nodes == null || nodes.size() < 2) throw new IllegalArgumentException("Invalid route");
            nodes = List.copyOf(nodes);
        }
    }
    public record Geometry(String mapId, List<Cell> zones, List<Route> routes) {
        public Geometry {
            if (mapId == null || !mapId.matches("[A-Za-z0-9_-]{1,80}"))
                throw new IllegalArgumentException("Invalid map ID");
            zones = List.copyOf(zones);
            routes = List.copyOf(routes);
            if (zones.size() > MAX_ZONES || routes.size() > 4) throw new IllegalArgumentException("Map overlay exceeds limits");
        }
    }
    private CFOverlayWire() {}

    public static byte[] pack(Geometry geometry) throws IOException {
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(rawOut)) {
            data.writeInt(GEOMETRY_MAGIC);
            data.writeShort(VERSION);
            byte[] map = geometry.mapId().getBytes(StandardCharsets.UTF_8);
            data.writeShort(map.length);
            data.write(map);
            data.writeInt(geometry.zones().size());
            for (Cell c : geometry.zones()) {
                data.writeInt(c.x()); data.writeShort(c.y()); data.writeInt(c.z()); data.writeByte(c.palette());
            }
            data.writeByte(geometry.routes().size());
            int count = 0;
            for (Route route : geometry.routes()) {
                count += route.nodes().size();
                if (count > MAX_PATH_NODES) throw new IOException("Too many path points");
                data.writeByte(route.lane()); data.writeInt(route.nodes().size());
                for (Point p : route.nodes()) {
                    data.writeFloat(p.x()); data.writeFloat(p.y()); data.writeFloat(p.z());
                }
            }
        }
        if (rawOut.size() > MAX_RAW) throw new IOException("Overlay exceeds raw size limit");
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(packed, new Deflater(Deflater.BEST_SPEED))) {
            stream.write(rawOut.toByteArray());
        }
        if (packed.size() > MAX_COMPRESSED) throw new IOException("Overlay exceeds compressed size limit");
        return packed.toByteArray();
    }

    public static Geometry unpack(byte[] compressed) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_COMPRESSED) throw new IOException("Bad compressed length");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (raw.size() + n > MAX_RAW) throw new IOException("Overlay decompression limit");
                raw.write(buf, 0, n);
            }
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()))) {
            if (data.readInt() != GEOMETRY_MAGIC || data.readUnsignedShort() != VERSION)
                throw new IOException("Unknown overlay protocol");
            int len = data.readUnsignedShort();
            if (len == 0 || len > 80) throw new IOException("Bad map ID length");
            String mapId = new String(data.readNBytes(len), StandardCharsets.UTF_8);
            int n = data.readInt();
            if (n < 0 || n > MAX_ZONES) throw new IOException("Bad zone count");
            List<Cell> cells = new ArrayList<>(n);
            for (int i=0; i<n; i++) cells.add(new Cell(data.readInt(), data.readShort(), data.readInt(), data.readUnsignedByte()));
            int routeCount = data.readUnsignedByte();
            if (routeCount == 0 || routeCount > 4) throw new IOException("Bad route count");
            List<Route> routes = new ArrayList<>();
            Set<Integer> laneIds = new HashSet<>();
            int total = 0;
            for (int i=0; i<routeCount; i++) {
                int lane = data.readUnsignedByte(), nodes = data.readInt();
                if (nodes < 2 || nodes > MAX_PATH_NODES || total + nodes > MAX_PATH_NODES || !laneIds.add(lane))
                    throw new IOException("Bad path count/lane ID");
                total += nodes;
                List<Point> points = new ArrayList<>(nodes);
                for (int j=0; j<nodes; j++) points.add(new Point(data.readFloat(), data.readFloat(), data.readFloat()));
                routes.add(new Route(lane, points));
            }
            if (data.available() != 0) throw new IOException("Trailing overlay data");
            return new Geometry(mapId, cells, routes);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid overlay data: " + e.getMessage(), e);
        }
    }

    public static List<byte[]> fragment(byte[] packed, int transferId) throws IOException {
        if (packed.length == 0 || packed.length > MAX_COMPRESSED) throw new IOException("Bad payload length");
        int count = (packed.length + PART_BYTES - 1) / PART_BYTES;
        if (count > MAX_FRAMES) throw new IOException("Too many overlay frames");
        CRC32 crc = new CRC32(); crc.update(packed);
        List<byte[]> frames = new ArrayList<>(count);
        for (int i=0; i<count; i++) {
            int from = i*PART_BYTES, to = Math.min(from+PART_BYTES, packed.length);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES+to-from);
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(FRAME_MAGIC); data.writeShort(VERSION);
            data.writeInt(transferId); data.writeShort(i); data.writeShort(count);
            data.writeInt(packed.length); data.writeInt((int) crc.getValue());
            data.write(packed, from, to-from); data.flush();
            frames.add(bytes.toByteArray());
        }
        return List.copyOf(frames);
    }

    /** One reassembly instance per client connection, kept in RAM only. */
    public static final class Receiver {
        private int transferId;
        private int count;
        private int length;
        private int checksum;
        private byte[][] parts;
        private int received;
        private Geometry completed;

        public void reset() {
            parts = null; received = 0; completed = null;
        }
        public Geometry accept(byte[] frame) throws IOException {
            if (frame == null || frame.length <= HEADER_BYTES || frame.length > HEADER_BYTES+PART_BYTES)
                throw new IOException("Invalid overlay frame size");
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(frame));
            if (data.readInt() != FRAME_MAGIC || data.readUnsignedShort() != VERSION)
                throw new IOException("Invalid overlay frame version");
            int id = data.readInt(), index = data.readUnsignedShort(), n = data.readUnsignedShort();
            int packedLen = data.readInt(), crc = data.readInt();
            if (n == 0 || n > MAX_FRAMES || index >= n || packedLen < 1 || packedLen > MAX_COMPRESSED
                    || (packedLen + PART_BYTES - 1)/PART_BYTES != n)
                throw new IOException("Invalid overlay chunk geometry");
            if (parts == null || transferId != id) {
                reset(); transferId = id; count = n; length = packedLen; checksum = crc;
                parts = new byte[n][];
            } else if (count != n || length != packedLen || checksum != crc) {
                reset(); throw new IOException("Overlay metadata mismatch");
            }
            int expected = index == n-1 ? packedLen - index*PART_BYTES : PART_BYTES;
            if (data.available() != expected) { reset(); throw new IOException("Overlay chunk size mismatch"); }
            byte[] bytes = data.readNBytes(expected);
            if (parts[index] != null) {
                if (!Arrays.equals(parts[index], bytes)) { reset(); throw new IOException("Conflicting overlay fragment"); }
                return completed;
            }
            parts[index] = bytes; received++;
            if (received < count) return null;
            byte[] merged = new byte[length];
            int offset=0;
            for (byte[] part : parts) { System.arraycopy(part, 0, merged, offset, part.length); offset+=part.length; }
            CRC32 got = new CRC32(); got.update(merged);
            if ((int) got.getValue() != checksum) { reset(); throw new IOException("Overlay checksum mismatch"); }
            completed = unpack(merged);
            // Release bulk fragments immediately. The decoded geometry is kept in RAM.
            parts = null;
            return completed;
        }
    }
}
