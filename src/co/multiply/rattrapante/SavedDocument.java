package co.multiply.rattrapante;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable full Automerge save. Equality and hashing compare encoded bytes,
 * not the visible content or history of loaded documents.
 *
 * <p>Import checks chunk framing only. Checksums, compressed contents and document
 * consistency are validated by Automerge when loading. This value owns no native
 * resources and can be shared independently of the original live document.</p>
 */
public final class SavedDocument {
    // Package access is reserved for SavedDocumentSupport, the internal copy bridge.
    final byte[] data;

    SavedDocument(byte[] ownedData) {
        data = ownedData;
    }

    /** Copy external bytes and check full-save framing without loading Automerge. */
    public static SavedDocument fromBytes(byte[] bytes) {
        byte[] copy = Objects.requireNonNull(bytes, "bytes").clone();
        checkFraming(copy);
        return new SavedDocument(copy);
    }

    /** Return a mutable copy in the standard Automerge binary format. */
    public byte[] toByteArray() {
        return data.clone();
    }

    /** Size of the encoded document, without copying its bytes. */
    public int byteSize() {
        return data.length;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SavedDocument saved && Arrays.equals(data, saved.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "SavedDocument[" + data.length + " bytes]";
    }

    private static IllegalArgumentException invalid(int offset, String reason) {
        return new IllegalArgumentException("Invalid Automerge saved-document framing at byte " + offset + ": " + reason);
    }

    // Matches storage/chunk.rs in the pinned Automerge source. Skip payloads;
    // never decompress, checksum or reconstruct operations here.
    private static void checkFraming(byte[] bytes) {
        if (bytes.length == 0) throw invalid(0, "expected a document snapshot");
        int offset = 0;
        while (offset < bytes.length) {
            int start = offset;
            // Magic (4), checksum (4), type (1), and at least one length byte.
            if (bytes.length - offset < 10) throw invalid(start, "truncated chunk header");
            if (bytes[offset] != (byte) 0x85 || bytes[offset + 1] != 0x6f
                    || bytes[offset + 2] != 0x4a || bytes[offset + 3] != (byte) 0x83) {
                throw invalid(start, "wrong format signature");
            }
            int type = Byte.toUnsignedInt(bytes[offset + 8]);
            if (start == 0 && type != 0) throw invalid(start, "expected a document snapshot first");
            if (type > 3) throw invalid(start, "unknown chunk type " + type);
            offset += 9;

            // Canonical unsigned LEB128 u64, matching the upstream parser.
            // Java arrays cannot hold a payload above 2^31-1.
            long length = 0;
            for (int group = 0; ; group++) {
                if (offset == bytes.length) throw invalid(start, "truncated chunk length");
                int octet = Byte.toUnsignedInt(bytes[offset++]);
                if (group == 9 && octet > 1) throw invalid(start, "chunk length overflows u64");
                length |= (long) (octet & 0x7f) << (group * 7);
                if ((octet & 0x80) == 0) {
                    if (group > 0 && octet == 0) throw invalid(start, "overlong chunk length");
                    break;
                }
            }
            if (length < 0 || length > bytes.length - offset) {
                throw invalid(start, "chunk payload exceeds available bytes");
            }
            offset += (int) length;
        }
    }
}
