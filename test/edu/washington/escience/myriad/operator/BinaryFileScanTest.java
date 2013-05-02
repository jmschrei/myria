package edu.washington.escience.myriad.operator;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import edu.washington.escience.myriad.DbException;
import edu.washington.escience.myriad.Schema;
import edu.washington.escience.myriad.TupleBatch;
import edu.washington.escience.myriad.Type;

/**
 * To test BinaryFileScan, and is is based on the code from FileScanTest
 * 
 * @author leelee
 * 
 */
public class BinaryFileScanTest {

  @Test
  /**
   * Test default BinaryFileScan that reads data bytes in big endian format.
   */
  public void testSimple() {
    Schema schema = new Schema(ImmutableList.of(Type.INT_TYPE, Type.INT_TYPE));
    String filename = "testdata" + File.separatorChar + "binaryfilescan" + File.separatorChar + "testSimple";
    generateSimpleBinaryFile(filename, 2);
    BinaryFileScan bfs = new BinaryFileScan(schema, filename);
    try {
      assertEquals(2, getRowCount(bfs));
    } catch (DbException e) {
      System.out.println("getRowCount in testSimple throws DbException.");
      e.printStackTrace();
    }
  }

  @Test
  /**
   * Test default BinaryFileScan that reads data bytes in big endian format with the bin file
   * that has the astronomy data schema.
   */
  public void testWithAstronomySchema() {
    Type[] typeAr = { Type.LONG_TYPE, // iOrder
        Type.FLOAT_TYPE, // mass
        Type.FLOAT_TYPE, // x
        Type.FLOAT_TYPE, // y
        Type.FLOAT_TYPE, // z
        Type.FLOAT_TYPE, // vx
        Type.FLOAT_TYPE, // vy
        Type.FLOAT_TYPE, // vz
        Type.FLOAT_TYPE, // rho
        Type.FLOAT_TYPE, // temp
        Type.FLOAT_TYPE, // hsmooth
        Type.FLOAT_TYPE, // metals
        Type.FLOAT_TYPE, // tform
        Type.FLOAT_TYPE, // eps
        Type.FLOAT_TYPE // phi
        };
    Schema schema = new Schema(Arrays.asList(typeAr));
    String filename =
        "testdata" + File.separatorChar + "binaryfilescan" + File.separatorChar + "testWithAstronomySchema";
    generateBinaryFile(filename, typeAr, 8);
    BinaryFileScan bfs = new BinaryFileScan(schema, filename);
    try {
      assertEquals(8, getRowCount(bfs));
    } catch (DbException e) {
      e.printStackTrace();
    }
  }

  @Test
  /**
   * Test BinaryFileScan with the real cosmo data bin file
   */
  public void testNumRowsFromCosmo24Star() {
    Type[] typeAr = { Type.LONG_TYPE, // iOrder
        Type.FLOAT_TYPE, // mass
        Type.FLOAT_TYPE, // x
        Type.FLOAT_TYPE, // y
        Type.FLOAT_TYPE, // z
        Type.FLOAT_TYPE, // vx
        Type.FLOAT_TYPE, // vy
        Type.FLOAT_TYPE, // vz
        Type.FLOAT_TYPE, // metals
        Type.FLOAT_TYPE, // tform
        Type.FLOAT_TYPE, // eps
        Type.FLOAT_TYPE, // phi
    };
    Schema schema = new Schema(Arrays.asList(typeAr));
    String filename =
        "testdata" + File.separatorChar + "binaryfilescan" + File.separatorChar + "cosmo50cmb.256g2bwK.00024.star.bin";
    BinaryFileScan bfs = new BinaryFileScan(schema, filename, true);
    try {
      assertEquals(1291, getRowCount(bfs));
    } catch (DbException e) {
      e.printStackTrace();
    }
  }

  /**
   * Generates a binary file with the given file name, type array and the number of row.
   * 
   * @param filename The filename to create.
   * @param typeAr The type array.
   * @param row The number of row.
   */
  private void generateBinaryFile(String filename, Type[] typeAr, int row) {
    try {
      RandomAccessFile raf = new RandomAccessFile(filename, "rw");
      for (int i = 0; i < row; i++) {
        for (Type element : typeAr) {
          switch (element) {
            case BOOLEAN_TYPE:
              raf.writeBoolean(true);
              break;
            case DOUBLE_TYPE:
              raf.writeDouble(i);
              break;
            case FLOAT_TYPE:
              raf.writeFloat(i);
              break;
            case INT_TYPE:
              raf.writeInt(i);
              break;
            case LONG_TYPE:
              raf.writeLong(i);
              break;
            default:
              throw new UnsupportedOperationException("can only write fix length field to bin file");
          }
        }
      }
      raf.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Generates a simple binary file with the given file name with the given number of row. The generated binary file
   * will contains two int in each row.
   * 
   * @param filename
   * @param row
   */
  private void generateSimpleBinaryFile(String filename, int row) {
    try {
      RandomAccessFile raf = new RandomAccessFile(filename, "rw");
      for (int i = 0; i < row; i++) {
        raf.writeInt(i);
        raf.writeInt(i);
      }
      raf.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Helper function used to run tests.
   * 
   * @param fileScan the FileScan object to be tested.
   * @return the number of rows in the file.
   * @throws DbException if the file does not match the given Schema.
   */
  private static int getRowCount(BinaryFileScan fileScan) throws DbException {
    fileScan.open(null);

    int count = 0;
    TupleBatch tb = null;
    while (!fileScan.eos()) {
      tb = fileScan.nextReady();
      if (tb != null) {
        count += tb.numTuples();
      }
    }
    return count;
  }
}
