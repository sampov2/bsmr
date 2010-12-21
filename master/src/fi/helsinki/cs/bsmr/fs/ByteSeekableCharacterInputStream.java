package fi.helsinki.cs.bsmr.fs;

import java.io.IOException;
import java.io.InputStream;


/**
 * An InputStream designed to help splitting massive UTF-8 files into non-overlapping parts 
 * where the boundaries are set in byte offsets instead of character-based calculations.
 * 
 * @author stsavola
 *
 */
public class ByteSeekableCharacterInputStream extends InputStream
{
	private InputStream source;
	
	private int bufferedCharacter;
	
	public ByteSeekableCharacterInputStream(InputStream source)
	{
		this.source = source;
		this.bufferedCharacter = -1;
	}

	@Override
	public long skip(long n) throws IOException 
	{
		if (n == 0) { return 0; }
		
		long ret = source.skip(n-1);
		
		int b = source.read(); // Do not increment bytesRead
		if (b == -1) { return ret; }
		
		if (b == '\n') {
			bufferedCharacter = '\n';
		} else {
			bufferedCharacter = -1;
		}
		
		return ret + 1;
	}
	
	/**
	 * Skip all characters of a partial UTF-8 character. This is used when skip() seeks us to
	 * the middle of a character.
	 * 
	 * @return The number of bytes skipped.
	 * @throws IOException If reading the original stream fails.
	 */
	public int skipPartialUTF8Character() throws IOException
	{
		 int b;
 
		 // We already have a buffered character => no need to find UTF
		 if (bufferedCharacter != -1) {
			 return 0;
		 }
		 
		 int ret = 0;
		 
		 // Read the incomplete unicode characters
		 do {
			 b = read();
			 //System.out.println("read "+b +" ("+((b & 0xc0) == 0x80)+")");
			 if (b == -1) {
				 // no more data to read, but we might be at the "border" of an UTF character..
				 // let's just ignore this situation
				 return 0;
			 }
			 ret++;
		 } while( (b & 0xc0) == 0x80); // read while bitmask is 10xxxxxx
		 
		 bufferedCharacter = b;
		 
		 return ret;
	}
	
	
	@Override
	public int read() throws IOException
	{
		if (bufferedCharacter != -1) {
			int b = bufferedCharacter;
			bufferedCharacter =-1;
			return b;
		}
		return source.read();
	}

	@Override
	public int read(byte[] b) throws IOException
	{
		return read(b, 0, b.length);
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException 
	{
		if (bufferedCharacter != -1) {
			b[off] = (byte)bufferedCharacter;
			bufferedCharacter = -1;
			if (len == 1) {
				return 1;
			}
			
			int ret = source.read(b, off+1, len-1);
			if (ret == -1) {
				// We've still managed to "read" the buffered
				// character
				return 1;
			}
			
			return 1+ret;
		}
		return source.read(b, off, len);
	}

	@Override
	public void close() throws IOException 
	{
		source.close();
	}
	
}