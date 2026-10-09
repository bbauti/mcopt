Zstandard's decompressor, v1.5.6, as its single file (build/single_file_libs/create_single_file_decoder.sh of the
release https://github.com/facebook/zstd/releases/download/v1.5.6/zstd-1.5.6.tar.gz, sha256
8c29e06cf42aacc1eafc4077ae2ec6c6fcb96a626157e0593d5e82a34fd403c1), unmodified. BSD license (LICENSE here), chosen of its
dual BSD / GPLv2 licensing. Compiled into the native library through ../mczstd.c, for reading Distant Horizons' saved
terrain (LodDhImport), which it compresses with zstd by default.
