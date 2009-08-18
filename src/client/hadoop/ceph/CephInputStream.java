// -*- mode:Java; tab-width:8; c-basic-offset:2; indent-tabs-mode:t -*- 
package org.apache.hadoop.fs.ceph;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
//import java.lang.IndexOutOfBoundsException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSInputStream;


/**
 * <p>
 * An {@link FSInputStream} for a CephFileSystem and corresponding
 * Ceph instance.
 */
class CephInputStream extends FSInputStream {

  private int bufferSize;

  //private Block[] blocks;

  private boolean closed;

  private int fileHandle;

  private long fileLength;

  private static boolean debug = false;

  //private long pos = 0;

  //private DataInputStream blockStream;

  //private long blockEnd = -1;

  private native int ceph_read(int fh, byte[] buffer, int buffer_offset, int length);
  private native long ceph_seek_from_start(int fh, long pos);
  private native long ceph_getpos(int fh);
  private native int ceph_close(int fh);
    
  /**
   * Create a new CephInputStream.
   * @param conf The system configuration. Unused.
   * @param fh The filehandle provided by Ceph to reference.
   * @param flength The current length of the file. If the length changes
   * you will need to close and re-open it to access the new data.
   */
  public CephInputStream(Configuration conf, int fh, long flength) {
    System.load(conf.get("fs.ceph.libDir")+"/libhadoopcephfs.so");
    System.load(conf.get("fs.ceph.libDir")+"/libceph.so");
    // Whoever's calling the constructor is responsible for doing the actual ceph_open
    // call and providing the file handle.
    fileLength = flength;
    fileHandle = fh;
    debug("CephInputStream constructor: initializing stream with fh "
	  + fh + " and file length " + flength);
      
  }
  //Ceph likes things to be closed before it shuts down,
  //so closing the IOStream stuff voluntarily is good
  public void finalize () throws Throwable {
    try {
      if (!closed) close();
    }
    finally { super.finalize(); }
  }

  public synchronized long getPos() throws IOException {
    return ceph_getpos(fileHandle);
  }

  @Override
  public synchronized int available() throws IOException {
      return (int) (fileLength - getPos());
    }

  public synchronized void seek(long targetPos) throws IOException {
    debug("CephInputStream.seek: Seeking to position " + targetPos +
	  " on fd " + fileHandle);
    if (targetPos > fileLength) {
      throw new IOException("CephInputStream.seek: failed seeking to position " + targetPos +
			    " on fd " + fileHandle + ": Cannot seek after EOF " + fileLength);
    }
    ceph_seek_from_start(fileHandle, targetPos);
  }

  /**
   * Failovers are handled by the Ceph code at a very low level;
   * if there are issues that can be solved by changing sources
   * they'll be dealt with before anybody even tries to call this method!
   * @return false.
   */
  public synchronized boolean seekToNewSource(long targetPos) {
    return false;
  }
    
    
  /**
   * Read a byte from the file.
   * @return the next byte.
   */
  @Override
  public synchronized int read() throws IOException {
      debug("CephInputStream.read: Reading a single byte from fd " + fileHandle
	    + " by calling general read function");

      byte result[] = new byte[1];
      if (getPos() >= fileLength) return -1;
      if (-1 == read(result, 0, 1)) return -1;
      return result[0];
    }

  /**
   * Read a specified number of bytes into a byte[] from the file.
   * @param buf[] the byte array to read into.
   * @param off the offset to start at in the file
   * @param len the number of bytes to read
   * @return 0 if successful, otherwise an error code.
   */
  @Override
  public synchronized int read(byte buf[], int off, int len) throws IOException {
      debug("CephInputStream.read: Reading " + len  + " bytes from fd " + fileHandle);
      
      if (closed) {
	throw new IOException("CephInputStream.read: cannot read " + len  + 
			      " bytes from fd " + fileHandle + ": stream closed");
      }
      if (null == buf) {
	throw new NullPointerException("Read buffer is null");
      }
      
      // check for proper index bounds
      if((off < 0) || (len < 0) || (off + len > buf.length)) {
	throw new IndexOutOfBoundsException("CephInputStream.read: Indices out of bounds for read: "
			    + "read length is " + len + ", buffer offset is " 
			    + off +", and buffer size is " + buf.length);
      }
      
      // ensure we're not past the end of the file
      if (getPos() >= fileLength) 
	{
	  debug("CephInputStream.read: cannot read " + len  + 
			     " bytes from fd " + fileHandle + ": current position is " +
			     getPos() + " and file length is " + fileLength);
	  
	  return -1;
	}
      // actually do the read
      int result = ceph_read(fileHandle, buf, off, len);
      if (result < 0)
	debug("CephInputStream.read: Reading " + len
			   + " bytes from fd " + fileHandle + " failed.");

      debug("CephInputStream.read: Reading " + len  + " bytes from fd " 
	    + fileHandle + ": succeeded in reading " + result + " bytes");   
      return result;
  }
  /**
   * Close the CephInputStream and release the associated filehandle.
   */
  @Override
  public void close() throws IOException {
    debug("CephOutputStream.close:enter");
    if (closed) {
      throw new IOException("Stream closed");
    }

    int result = ceph_close(fileHandle);
    if (result != 0) {
      throw new IOException("Close failed!");
    }
    closed = true;
    debug("CephOutputStream.close:exit");
  }

  /**
   * Marks are not supported.
   * @return false
   */
  @Override
  public boolean markSupported() {
    return false;
  }

  /**
   * Since marking isn't supported, this function throws an IOException.
   * @throws IOException whenever called.
   */
  @Override
  public void mark(int readLimit) {
    throw new IOException("Mark not supported");
  }

  /**
   * Since marks aren't supported, this function throws an IOException.
   * @throws IOException whenever called.
   */
  @Override
  public void reset() throws IOException {
    throw new IOException("Mark not supported");
  }

  private void debug(String out) {
    if (debug) System.out.println(out);
  }
}
