package mcopt.metal.lod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Far terrain between mcopt-server (LodServerService) and mcopt clients (LodRemote), compiled into both. The client asks, the server answers:
 * <ul>
 * <li>Hello (client, per dimension it enters) -> DimInfo: whether the server generates far terrain there and sends its
 * saved chunks, its sea level, the farthest it serves, and a token naming that world (the client's cache key).</li>
 * <li>TileReq (tile keys, most wanted first; reset drops what the server still has queued for the client) -> Tile each: a
 * generated tile's structure (LodStructure), painted by the client.</li>
 * <li>RegionReq (regions) -> Manifest each: the save times of the region's chunks (its file's header).</li>
 * <li>ChunkReq (chunks the client lacks or has older) -> Chunks: their columns (LodStructure.encodeChunk), deflated.</li>
 * </ul>
 * Everything is keyed by the dimension's id, so a reply that arrives after the player changed dimension is dropped.
 */
public final class LodNet {
	/** The protocol: a side that sees another refuses to talk. */
	public static final int PROTOCOL = 2;
	/** Most keys a request carries (serverbound payloads stay under 32 KB). */
	public static final int MAX_KEYS = 3000;
	/** How far a level-0 tile is made (blocks past its size; doubles per level): half the largest preset's 2048-cell window and a tile. */
	public static final int LEVEL0_REACH = 1024 + 128;

	/** Whether a server serving `radius` chunks makes the tile of `level` centered (dx, dz) blocks from the player (both sides ask). */
	public static boolean serves(int radius, int level, double dx, double dz) {
		if (level < 0 || level > 15) return false;
		double span = LodTile.SIZE << level;
		double r = Math.min(radius * 16.0, LEVEL0_REACH * Math.pow(2, level)) + span;
		return Math.abs(dx) <= r && Math.abs(dz) <= r;
	}

	private LodNet() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("mcopt", path);
	}

	public record Hello(int protocol, String dimension) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = new Type<>(id("lod_hello"));
		public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of((b, v) -> b.writeVarInt(v.protocol).writeUtf(v.dimension, 256),
			b -> new Hello(b.readVarInt(), b.readUtf(256)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** radius: in chunks; token: names the world (a server that resets its map gets a new one). */
	public record DimInfo(int protocol, String dimension, boolean generate, boolean chunks, int seaLevel, int radius, long token)
		implements CustomPacketPayload {
		public static final Type<DimInfo> TYPE = new Type<>(id("lod_dim"));
		public static final StreamCodec<FriendlyByteBuf, DimInfo> CODEC = StreamCodec.of(
			(b, v) -> b.writeVarInt(v.protocol).writeUtf(v.dimension, 256).writeBoolean(v.generate).writeBoolean(v.chunks).writeVarInt(v.seaLevel)
				.writeVarInt(v.radius).writeLong(v.token),
			b -> new DimInfo(b.readVarInt(), b.readUtf(256), b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readLong()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TileReq(String dimension, boolean reset, long[] keys) implements CustomPacketPayload {
		public static final Type<TileReq> TYPE = new Type<>(id("lod_tile_req"));
		public static final StreamCodec<FriendlyByteBuf, TileReq> CODEC = StreamCodec.of((b, v) -> b.writeUtf(v.dimension, 256).writeBoolean(v.reset)
			.writeLongArray(v.keys), b -> new TileReq(b.readUtf(256), b.readBoolean(), readLongs(b)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A tile's structure, deflated (LodStructure.encodeTile); empty: the server won't make this one (past its radius). */
	public record Tile(String dimension, long key, byte[] data) implements CustomPacketPayload {
		public static final Type<Tile> TYPE = new Type<>(id("lod_tile"));
		public static final StreamCodec<FriendlyByteBuf, Tile> CODEC = StreamCodec.of((b, v) -> b.writeUtf(v.dimension, 256).writeLong(v.key)
			.writeByteArray(v.data), b -> new Tile(b.readUtf(256), b.readLong(), b.readByteArray(1 << 20)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Regions as (rx << 32 | rz & 0xFFFFFFFF). */
	public record RegionReq(String dimension, long[] regions) implements CustomPacketPayload {
		public static final Type<RegionReq> TYPE = new Type<>(id("lod_region_req"));
		public static final StreamCodec<FriendlyByteBuf, RegionReq> CODEC = StreamCodec.of((b, v) -> b.writeUtf(v.dimension, 256)
			.writeLongArray(v.regions), b -> new RegionReq(b.readUtf(256), readLongs(b)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A region's 1024 chunks' save times (seconds, its file's header; 0: no chunk there), z-major as the file has them. */
	public record Manifest(String dimension, int rx, int rz, int[] stamps) implements CustomPacketPayload {
		public static final Type<Manifest> TYPE = new Type<>(id("lod_manifest"));
		public static final StreamCodec<FriendlyByteBuf, Manifest> CODEC = StreamCodec.of((b, v) -> b.writeUtf(v.dimension, 256).writeInt(v.rx)
			.writeInt(v.rz).writeVarIntArray(v.stamps), b -> new Manifest(b.readUtf(256), b.readInt(), b.readInt(), b.readVarIntArray(1024)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Chunks as ChunkPos.pack(x, z). */
	public record ChunkReq(String dimension, long[] chunks) implements CustomPacketPayload {
		public static final Type<ChunkReq> TYPE = new Type<>(id("lod_chunk_req"));
		public static final StreamCodec<FriendlyByteBuf, ChunkReq> CODEC = StreamCodec.of((b, v) -> b.writeUtf(v.dimension, 256)
			.writeLongArray(v.chunks), b -> new ChunkReq(b.readUtf(256), readLongs(b)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Deflated: a count, then per chunk its save time and its columns (LodStructure.encodeChunk). */
	public record Chunks(String dimension, byte[] data) implements CustomPacketPayload {
		public static final Type<Chunks> TYPE = new Type<>(id("lod_chunks"));
		public static final StreamCodec<FriendlyByteBuf, Chunks> CODEC = StreamCodec.of((b, v) -> b.writeUtf(v.dimension, 256).writeByteArray(v.data),
			b -> new Chunks(b.readUtf(256), b.readByteArray(1 << 20)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static long[] readLongs(FriendlyByteBuf b) {
		int n = b.readVarInt();
		if (n < 0 || n > MAX_KEYS) throw new IllegalArgumentException("too many keys: " + n);
		long[] out = new long[n];
		for (int i = 0; i < n; i++) out[i] = b.readLong();
		return out;
	}
}
