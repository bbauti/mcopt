package mcopt.metal.lod;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The far terrain's structure as bytes, for a server to send what it generated and read from its saves to its clients (shared by the client mod and
 * mcopt-server; nothing here touches a client class): a generated tile's structure (LodNoise without paint: heights, water, blocks, biomes, trees,
 * plants), and a saved chunk's columns (LodChunks.Snapshot). Blocks and biomes go by name in a palette per record (numbers differ between a server
 * and its clients), arrays planar (the bytes of every value's lowest byte, then the next...: neighbors share their high bytes), the whole deflated.
 */
final class LodStructure {
	/** The format: a reader refuses any other (2: a tile's plants are any block without collision; the client keeps its own). */
	static final int VERSION = 2;

	private LodStructure() {
	}

	/** A structure tile (level, origin and size from its key) as bytes. */
	static byte[] encodeTile(LodTile t) {
		Out o = new Out();
		o.i32(VERSION);
		o.i32(t.level);
		o.i32(t.voidY);
		o.u8((t.waterKnown ? 1 : 0) | (t.impostorTrees ? 2 : 0));
		int n = t.cells();
		Palette<BlockState> states = new Palette<>();
		Palette<Holder<Biome>> biomes = new Palette<>();
		short[] st = new short[n], below = new short[n], canopy = new short[n], trunk = new short[n], plantLo = new short[n], plantHi = new short[n],
			bio = new short[n];
		byte[] flags = new byte[n];
		for (int i = 0; i < n; i++) {
			st[i] = states.of(t.state[i]);
			below[i] = states.of(t.belowState[i]);
			canopy[i] = states.of(t.canopyState[i]);
			trunk[i] = states.of(t.trunk[i]);
			plantLo[i] = states.of(t.plantLower[i]);
			plantHi[i] = states.of(t.plantUpper[i]);
			bio[i] = biomes.of(t.biome[i]);
			flags[i] = (byte) (t.standing[i] ? 1 : 0);
		}
		o.names(states, LodStructure::stateName);
		o.names(biomes, LodStructure::biomeName);
		o.shorts(n, t.height, t.water, t.ground, t.canopyLo, t.canopyHi, st, below, canopy, trunk, plantLo, plantHi, bio);
		o.ints(t.crownRuns, n);
		o.bytes(t.plantBlocks, n);
		o.bytes(t.impostor, n);
		o.bytes(flags, n);
		return o.deflated();
	}

	/**
	 * A structure tile from bytes, for tile (level, tx, tz); states and biomes by name through the client's lookups (an unknown block reads as stone
	 * or leaves, or none for a plant; an unknown biome as fallback). Null when the bytes aren't this format.
	 */
	static @Nullable LodTile decodeTile(byte[] bytes, int level, int tx, int tz, Function<String, @Nullable BlockState> stateOf,
		Function<String, @Nullable Holder<Biome>> biomeOf, Holder<Biome> fallback) {
		try {
			In in = new In(inflate(bytes));
			if (in.i32() != VERSION || in.i32() != level) return null;
			LodTile t = new LodTile(level, tx, tz);
			t.voidY = in.i32();
			int f = in.u8();
			t.waterKnown = (f & 1) != 0;
			t.impostorTrees = (f & 2) != 0;
			t.source = LodTile.SOURCE_NOISE;
			int n = t.cells();
			BlockState[] states = in.names(stateOf, BlockState[]::new);
			@SuppressWarnings("unchecked")
			Holder<Biome>[] biomes = in.names(biomeOf, Holder[]::new);
			in.shorts(n, t.height, t.water, t.ground, t.canopyLo, t.canopyHi);
			short[] idx = new short[n];
			// (a block this client doesn't know (a server's mod): stone for the ground, oak leaves for a tree, so the cell keeps a look)
			values(in, idx, n, states, STONE, null, t.state);
			values(in, idx, n, states, STONE, null, t.belowState);
			values(in, idx, n, states, LEAVES, null, t.canopyState);
			values(in, idx, n, states, null, null, t.trunk);
			values(in, idx, n, states, null, null, t.plantLower);
			values(in, idx, n, states, null, null, t.plantUpper);
			values(in, idx, n, biomes, fallback, fallback, t.biome);
			in.ints(t.crownRuns, n);
			in.bytes(t.plantBlocks, n);
			in.bytes(t.impostor, n);
			byte[] flags = new byte[n];
			in.bytes(flags, n);
			for (int i = 0; i < n; i++) {
				t.standing[i] = (flags[i] & 1) != 0;
				// a tree without its top block: no tree (the ground stays)
				if (t.canopyHi[i] >= t.canopyLo[i] && t.canopyState[i] == null) {
					t.canopyHi[i] = Short.MIN_VALUE;
					t.standing[i] = false;
				}
			}
			return t;
		} catch (RuntimeException | DataFormatException e) {
			return null;
		}
	}

	/** A chunk's columns as bytes (the snapshot's solid runs and section copies are not sent: the real-terrain occluder's). */
	static void encodeChunk(LodChunks.Snapshot s, Out o) {
		Palette<BlockState> states = new Palette<>();
		Palette<Holder<Biome>> biomes = new Palette<>();
		short[] top = new short[256], under = new short[256], crown = new short[256], leaf = new short[256], trunk = new short[256], bio = new short[256];
		short[] above = new short[256 * 3];
		for (int i = 0; i < 256; i++) {
			top[i] = states.of(s.top()[i]);
			under[i] = states.of(s.under()[i]);
			crown[i] = states.of(s.crown()[i]);
			leaf[i] = states.of(s.crownLeaf()[i]);
			trunk[i] = states.of(s.trunk()[i]);
			bio[i] = biomes.of(s.biome()[i]);
		}
		for (int i = 0; i < 256 * 3; i++) above[i] = states.of(s.above()[i]);
		o.i32(s.chunkX());
		o.i32(s.chunkZ());
		o.i32(s.minY());
		o.names(states, LodStructure::stateName);
		o.names(biomes, LodStructure::biomeName);
		o.shorts(256, s.height(), s.water(), s.crownLo(), s.crownHi());
		o.ints(s.crownRuns(), 256);
		o.shorts(256, top, under, crown, leaf, trunk, bio);
		o.shorts(256 * 3, above);
	}

	/** A chunk's columns from bytes (as encodeChunk wrote them, from in's position). Null blocks where this client lacks them. */
	@SuppressWarnings("unchecked")
	static LodChunks.Snapshot decodeChunk(In in, Function<String, @Nullable BlockState> stateOf, Function<String, @Nullable Holder<Biome>> biomeOf,
		Holder<Biome> fallback) {
		int cx = in.i32(), cz = in.i32(), minY = in.i32();
		BlockState[] states = in.names(stateOf, BlockState[]::new);
		Holder<Biome>[] biomes = in.names(biomeOf, Holder[]::new);
		short[] height = new short[256], water = new short[256], crownLo = new short[256], crownHi = new short[256];
		int[] runs = new int[256];
		in.shorts(256, height, water, crownLo, crownHi);
		in.ints(runs, 256);
		short[] idx = new short[256 * 3];
		BlockState[] top = new BlockState[256], under = new BlockState[256], crown = new BlockState[256], leaf = new BlockState[256], trunk = new BlockState[256];
		BlockState[] above = new BlockState[256 * 3];
		Holder<Biome>[] biome = new Holder[256];
		// (a block this client lacks: stone, so the column still stands)
		values(in, idx, 256, states, STONE, Blocks.AIR.defaultBlockState(), top);
		values(in, idx, 256, states, STONE, Blocks.AIR.defaultBlockState(), under);
		values(in, idx, 256, states, null, null, crown);
		values(in, idx, 256, states, null, null, leaf);
		values(in, idx, 256, states, null, null, trunk);
		values(in, idx, 256, biomes, fallback, fallback, biome);
		values(in, idx, 256 * 3, states, null, null, above);
		for (int i = 0; i < 256; i++) {
			// a crown whose leaves this client lacks: none
			if (crown[i] == null && crownLo[i] != Short.MIN_VALUE) crownLo[i] = Short.MIN_VALUE;
			if (crown[i] != null && leaf[i] == null) leaf[i] = crown[i];
		}
		return new LodChunks.Snapshot(cx, cz, top, under, height, water, biome, crown, leaf, crownLo, crownHi, runs, trunk, above, null, null, 0, minY);
	}

	/** A block state's name: the game's own codec, as JSON (the disk cache's names too). */
	static String stateName(BlockState s) {
		return BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).result().map(Object::toString).orElse("");
	}

	static @Nullable BlockState parseState(String name) {
		if (name.isEmpty()) return null;
		try {
			return BlockState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(name)).result().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	static String biomeName(Holder<Biome> b) {
		return b.unwrapKey().map(k -> k.identifier().toString()).orElse("");
	}

	private static final BlockState STONE = Blocks.STONE.defaultBlockState(), LEAVES = Blocks.OAK_LEAVES.defaultBlockState();

	/** The next n palette indices' values into out: `unknown` where a name was sent that isn't one here, `none` for index 0. */
	private static <T> void values(In in, short[] idx, int n, T[] table, @Nullable T unknown, @Nullable T none, T[] out) {
		in.shorts(n, idx);
		for (int i = 0; i < n; i++) {
			int k = idx[i] & 0xFFFF;
			T v = k > 0 && k < table.length ? table[k] : null;
			out[i] = v != null ? v : k != 0 ? unknown : none;
		}
	}

	/** Values numbered in order of first use; 0 is null. */
	static final class Palette<T> {
		final java.util.ArrayList<T> values = new java.util.ArrayList<>();
		private final java.util.IdentityHashMap<T, Integer> ids = new java.util.IdentityHashMap<>();

		Palette() {
			this.values.add(null);
		}

		short of(@Nullable T v) {
			if (v == null) return 0;
			Integer id = this.ids.get(v);
			if (id == null) {
				id = this.values.size();
				if (id > 0xFFFF) return 0;
				this.values.add(v);
				this.ids.put(v, id);
			}
			return (short) (int) id;
		}
	}

	static byte[] inflate(byte[] bytes) throws DataFormatException {
		Inflater inf = new Inflater();
		try {
			inf.setInput(bytes);
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, bytes.length * 6));
			byte[] buf = new byte[65536];
			while (!inf.finished()) {
				int k = inf.inflate(buf);
				if (k == 0 && (inf.needsInput() || inf.needsDictionary())) throw new DataFormatException("truncated");
				out.write(buf, 0, k);
				if (out.size() > 64 << 20) throw new DataFormatException("too large");
			}
			return out.toByteArray();
		} finally {
			inf.end();
		}
	}

	/** Bytes being written: big-endian scalars, arrays planar; deflated() at the end. */
	static final class Out {
		private byte[] buf = new byte[1 << 16];
		private int size;

		private void ensure(int more) {
			if (this.size + more > this.buf.length) this.buf = java.util.Arrays.copyOf(this.buf, Math.max(this.buf.length * 2, this.size + more));
		}

		void u8(int v) {
			this.ensure(1);
			this.buf[this.size++] = (byte) v;
		}

		void i32(int v) {
			this.ensure(4);
			this.buf[this.size++] = (byte) (v >>> 24);
			this.buf[this.size++] = (byte) (v >>> 16);
			this.buf[this.size++] = (byte) (v >>> 8);
			this.buf[this.size++] = (byte) v;
		}

		void bytes(byte[] v, int n) {
			this.ensure(n);
			System.arraycopy(v, 0, this.buf, this.size, n);
			this.size += n;
		}

		void shorts(int n, short[]... arrays) {
			for (short[] v : arrays) {
				this.ensure(2 * n);
				for (int i = 0; i < n; i++) {
					this.buf[this.size + i] = (byte) v[i];
					this.buf[this.size + n + i] = (byte) (v[i] >>> 8);
				}
				this.size += 2 * n;
			}
		}

		void ints(int[] v, int n) {
			this.ensure(4 * n);
			for (int i = 0; i < n; i++) {
				int x = v[i];
				this.buf[this.size + i] = (byte) x;
				this.buf[this.size + n + i] = (byte) (x >>> 8);
				this.buf[this.size + 2 * n + i] = (byte) (x >>> 16);
				this.buf[this.size + 3 * n + i] = (byte) (x >>> 24);
			}
			this.size += 4 * n;
		}

		<T> void names(Palette<T> p, Function<T, String> name) {
			try {
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				DataOutputStream d = new DataOutputStream(b);
				d.writeShort(p.values.size() - 1);
				for (int i = 1; i < p.values.size(); i++) d.writeUTF(name.apply(p.values.get(i)));
				this.bytes(b.toByteArray(), b.size());
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		byte[] raw() {
			return java.util.Arrays.copyOf(this.buf, this.size);
		}

		byte[] deflated() {
			Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
			try {
				d.setInput(this.buf, 0, this.size);
				d.finish();
				ByteArrayOutputStream out = new ByteArrayOutputStream(this.size / 4 + 64);
				byte[] b = new byte[65536];
				while (!d.finished()) out.write(b, 0, d.deflate(b));
				return out.toByteArray();
			} finally {
				d.end();
			}
		}
	}

	/** Bytes being read (as Out wrote them). Throws a RuntimeException when they run out. */
	static final class In {
		private final ByteBuffer buf;

		In(byte[] bytes) {
			this.buf = ByteBuffer.wrap(bytes);
		}

		int u8() {
			return this.buf.get() & 255;
		}

		int i32() {
			return this.buf.getInt();
		}

		void bytes(byte[] v, int n) {
			this.buf.get(v, 0, n);
		}

		void shorts(int n, short[]... arrays) {
			byte[] a = this.buf.array();
			for (short[] v : arrays) {
				int p = this.buf.position();
				if (p + 2 * n > this.buf.limit()) throw new java.nio.BufferUnderflowException();
				for (int i = 0; i < n; i++) v[i] = (short) ((a[p + i] & 255) | (a[p + n + i] & 255) << 8);
				this.buf.position(p + 2 * n);
			}
		}

		void ints(int[] v, int n) {
			byte[] a = this.buf.array();
			int p = this.buf.position();
			if (p + 4 * n > this.buf.limit()) throw new java.nio.BufferUnderflowException();
			for (int i = 0; i < n; i++) v[i] = (a[p + i] & 255) | (a[p + n + i] & 255) << 8 | (a[p + 2 * n + i] & 255) << 16 | (a[p + 3 * n + i] & 255) << 24;
			this.buf.position(p + 4 * n);
		}

		<T> T[] names(Function<String, T> parse, java.util.function.IntFunction<T[]> array) {
			int count = this.buf.getShort() & 0xFFFF;
			T[] out = array.apply(count + 1);
			for (int i = 1; i <= count; i++) {
				int p = this.buf.position(), len = this.buf.getShort() & 0xFFFF;
				if (len > this.buf.remaining()) throw new java.nio.BufferUnderflowException();
				this.buf.position(p + 2 + len);
				String s;
				try {
					s = new java.io.DataInputStream(new java.io.ByteArrayInputStream(this.buf.array(), p, 2 + len)).readUTF();
				} catch (IOException e) {
					s = "";
				}
				out[i] = parse.apply(s);
			}
			return out;
		}
	}
}
