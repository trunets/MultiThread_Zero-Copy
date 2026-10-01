/*
 * Client Requirements:
 * - Connect to Server via TCP.
 * - Request file list and file information.
 * - Split a file into 10 non-overlapping ranges.
 * - Use 10 workers, each with its own connection, offset, and length.
 * - Download assigned ranges using GET.
 * - Merge parts or write directly using file offsets.
 * - Verify final file size/hash.
 * - Compare 1 vs 10 workers and Traditional I/O vs NIO.
 */