package net.sourceforge.opencamera;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

public class HDRProcessor {
	private static final String TAG = "HDRProcessor";
	
	private Context context = null;

	enum HDRAlgorithm {
		HDRALGORITHM_AVERAGE,
		HDRALGORITHM_STANDARD
	};
	
	HDRProcessor(Context context) {
		this.context = context;
	}

	/** Given a set of data Xi and Yi, this function estimates a relation between X and Y
	 *  using linear least squares.
	 *  We use it to modify the pixels of images taken at the brighter or darker exposure
	 *  levels, to estimate what the pixel should be at the "base" exposure.
	 */
	private class ResponseFunction {
		float parameter = 0.0f;

		/** Computes the response function.
		 * @param x_samples List of Xi samples. Must be at least 3 samples.
		 * @param y_samples List of Yi samples. Must be same length as x_samples.
		 * @param weights List of weights. Must be same length as x_samples.
		 */
		ResponseFunction(int id, List<Double> x_samples, List<Double> y_samples, List<Double> weights) {
			if( MyDebug.LOG )
				Log.d(TAG, "ResponseFunction");

			if( x_samples.size() != y_samples.size() ) {
				if( MyDebug.LOG )
					Log.e(TAG, "unequal number of samples");
				// throw RuntimeException, as this is a programming error
				throw new RuntimeException();
			}
			else if( x_samples.size() != weights.size() ) {
				if( MyDebug.LOG )
					Log.e(TAG, "unequal number of samples");
				// throw RuntimeException, as this is a programming error
				throw new RuntimeException();
			}
			else if( x_samples.size() <= 3 ) {
				if( MyDebug.LOG )
					Log.e(TAG, "not enough samples");
				// throw RuntimeException, as this is a programming error
				throw new RuntimeException();
			}
			
			double numer = 0.0;
			double denom = 0.0;
			for(int i=0;i<x_samples.size();i++) {
				double x = x_samples.get(i);
				double y = y_samples.get(i);
				double w = weights.get(i);
				numer += w*x*y;
				denom += w*x*x;
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "numer = " + numer);
				Log.d(TAG, "denom = " + denom);
			}
			
			if( denom < 1.0e-5 ) {
				if( MyDebug.LOG )
					Log.e(TAG, "denom too small");
				parameter = 1.0f;
			}
			else {
				parameter = (float)(numer / denom);
			}
			if( MyDebug.LOG )
				Log.d(TAG, "parameter = " + parameter);

			if( MyDebug.LOG ) {
				// log samples to a CSV file
				File file = new File(Environment.getExternalStorageDirectory().getPath() + "/net.sourceforge.opencamera.hdr_samples_" + id + ".csv");
				if( file.exists() ) {
					file.delete();
				}
				try {
					FileWriter writer = new FileWriter(file);
					writer.append("X,Y\n");
					writer.append("Parameter," + parameter + "\n");
					for(int i=0;i<x_samples.size();i++) {
						Log.d(TAG, "log: " + i + " / " + x_samples.size());
						double x = x_samples.get(i);
						double y = y_samples.get(i);
						writer.append(x + "," + y + "\n");
					}
					writer.close();
		        	MediaScannerConnection.scanFile(context, new String[] { file.getAbsolutePath() }, null, null);
				}
				catch (IOException e) {
					Log.e(TAG, "failed to open csv file");
					e.printStackTrace();
				}
			}
		}
	}

	/** Converts a list of bitmaps into a HDR image, which is then tonemapped to a final RGB image.
	 * @param bitmaps The list of bitmaps, which should be in order of increasing brightness (exposure).
	 *                The resultant image is stored in the first bitmap.
	 *                Currently only supports a list of 3 images, the 2nd should be at the desired exposure
	 *                level for the resultant image.
	 *                The bitmaps must all be the same resolution.
	 */
	public void processHDR(List<Bitmap> bitmaps) {
		if( MyDebug.LOG )
			Log.d(TAG, "processHDR");
		int n_bitmaps = bitmaps.size();
		if( n_bitmaps != 3 ) {
			if( MyDebug.LOG )
				Log.e(TAG, "n_bitmaps should be 3, not " + n_bitmaps);
			// throw RuntimeException, as this is a programming error
			throw new RuntimeException();
		}
		for(int i=1;i<n_bitmaps;i++) {
			if( bitmaps.get(i).getWidth() != bitmaps.get(0).getWidth() ||
				bitmaps.get(i).getHeight() != bitmaps.get(0).getHeight() ) {
				if( MyDebug.LOG )
					Log.e(TAG, "bitmaps not of same resolution");
				throw new RuntimeException();
			}
		}
		
		//final HDRAlgorithm algorithm = HDRAlgorithm.HDRALGORITHM_AVERAGE;
		final HDRAlgorithm algorithm = HDRAlgorithm.HDRALGORITHM_STANDARD;
		
		switch( algorithm ) {
		case HDRALGORITHM_AVERAGE:
			processHDRAverage(bitmaps);
			break;
		case HDRALGORITHM_STANDARD:
			processHDRCore(bitmaps);
			break;
		default:
			if( MyDebug.LOG )
				Log.e(TAG, "unknown algorithm " + algorithm);
			// throw RuntimeException, as this is a programming error
			throw new RuntimeException();
		}
	}

	/** Creates a ResponseFunction to estimate how pixels from the in_bitmap should be adjusted to
	 *  match the exposure level of out_bitmap.
	 */
	private ResponseFunction createFunctionFromBitmaps(int id, Bitmap in_bitmap, Bitmap out_bitmap) {
		if( MyDebug.LOG )
			Log.d(TAG, "createFunctionFromBitmaps");
		List<Double> x_samples = new ArrayList<Double>();
		List<Double> y_samples = new ArrayList<Double>();
		List<Double> weights = new ArrayList<Double>();

		final int n_samples_c = 100;
		final int n_w_samples = (int)Math.sqrt(n_samples_c);
		final int n_h_samples = n_samples_c/n_w_samples;
		
		for(int y=0;y<n_h_samples;y++) {
			double alpha = ((double)y+1.0) / ((double)n_h_samples+1.0);
			int y_coord = (int)(alpha * in_bitmap.getHeight());
			for(int x=0;x<n_w_samples;x++) {
				double beta = ((double)x+1.0) / ((double)n_w_samples+1.0);
				int x_coord = (int)(beta * in_bitmap.getWidth());
				if( MyDebug.LOG )
					Log.d(TAG, "sample response from " + x_coord + " , " + y_coord);
				int in_col = in_bitmap.getPixel(x_coord, y_coord);
				int out_col = out_bitmap.getPixel(x_coord, y_coord);
				double in_value = averageRGB(in_col);
				double out_value = averageRGB(out_col);
				x_samples.add(in_value);
				y_samples.add(out_value);
				//double weight = calculateWeight(in_value);
				//weights.add(weight);
			}
		}
		{
			// calculate weights
			double min_value = x_samples.get(0);
			double max_value = x_samples.get(0);
			for(int i=1;i<x_samples.size();i++) {
				double value = x_samples.get(i);
				if( value < min_value )
					min_value = value;
				if( value > max_value )
					max_value = value;
			}
			double med_value = 0.5*(min_value + max_value);
			if( MyDebug.LOG ) {
				Log.d(TAG, "min_value: " + min_value);
				Log.d(TAG, "max_value: " + max_value);
				Log.d(TAG, "med_value: " + med_value);
			}
			for(int i=0;i<x_samples.size();i++) {
				double value = x_samples.get(i);
				double weight = (value <= med_value) ? value - min_value : max_value - value;
				weights.add(weight);
			}
		}
		
		ResponseFunction function = new ResponseFunction(id, x_samples, y_samples, weights);
		return function;
	}

	/** Calculates average of RGB values for the supplied color.
	 */
	private double averageRGB(int color) {
		int r = (color & 0xFF0000) >> 16;
		int g = (color & 0xFF00) >> 8;
		int b = (color & 0xFF);
		double value = (r + g + b)/3.0;
		return value;
	}
	
	/** Calculates the luminance for an RGB colour.
	 */
	private double calculateLuminance(double r, double g, double b) {
		double value = 0.27*r + 0.67*g + 0.06*b;
		return value;
	}
	
	/*final float A = 0.15f;
	final float B = 0.50f;
	final float C = 0.10f;
	final float D = 0.20f;
	final float E = 0.02f;
	final float F = 0.30f;
	final float W = 11.2f;
	
	float Uncharted2Tonemap(float x) {
		return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
	}*/

	/** Converts a HDR brightness to a 0-255 value.
	 * @param hdr The input HDR brightness.
	 * @param l_avg The log average luminance of the HDR image. That is, exp( sum{log(Li)}/N ).
	 */
	private void tonemap(int [] rgb, float [] hdr, float l_avg) {
		// simple clamp:
		/*for(int i=0;i<3;i++) {
			rgb[i] = (int)hdr[i];
			if( rgb[i] > 255 )
				rgb[i] = 255;
		}*/
		/*
		// exponential:
		final double exposure_c = 1.2 / 255.0;
		int rgb = (int)(255.0*(1.0 - Math.exp(- hdr * exposure_c)));
		*/
		// Reinhard (Global):
		//final double scale_c = 0.5*255.0;
		//final double scale_c = 1.0*255.0;
		final float scale_c = l_avg / 0.5f;
		/*for(int i=0;i<3;i++)
			rgb[i] = (int)(255.0 * ( hdr[i] / (scale_c + hdr[i]) ));
			*/
		float max_hdr = hdr[0];
		if( hdr[1] > max_hdr )
			max_hdr = hdr[1];
		if( hdr[2] > max_hdr )
			max_hdr = hdr[2];
		float scale = 255.0f / ( scale_c + max_hdr );
		for(int i=0;i<3;i++)
			rgb[i] = (int)(scale * hdr[i]);
		// Uncharted 2 Hable
		/*final float exposure_bias = 2.0f / 255.0f;
		final float white_scale = 255.0f / Uncharted2Tonemap(W);
		for(int i=0;i<3;i++) {
			float curr = Uncharted2Tonemap(exposure_bias * hdr[i]);
			rgb[i] = (int)(curr * white_scale);
		}*/
	}
	
	class HDRWriterThread extends Thread {
		int y_start = 0, y_stop = 0;
		List<Bitmap> bitmaps;
		ResponseFunction [] response_functions;
		float avg_luminance = 0.0f;

		int n_bitmaps = 0;
		Bitmap bm = null;
		int [][] buffers = null;
		
		HDRWriterThread(int y_start, int y_stop, List<Bitmap> bitmaps, ResponseFunction [] response_functions, float avg_luminance) {
			if( MyDebug.LOG )
				Log.d(TAG, "thread " + this.getId() + " will process " + y_start + " to " + y_stop);
			this.y_start = y_start;
			this.y_stop = y_stop;
			this.bitmaps = bitmaps;
			this.response_functions = response_functions;
			this.avg_luminance = avg_luminance;

			this.n_bitmaps = bitmaps.size();
			this.bm = bitmaps.get(0);
			this.buffers = new int[n_bitmaps][];
			for(int i=0;i<n_bitmaps;i++) {
				buffers[i] = new int[bm.getWidth()];
			}
		}
		
		public void run() {
			float [] hdr = new float[3];
			int [] rgb = new int[3];

			for(int y=y_start;y<y_stop;y++) {
				if( MyDebug.LOG ) {
					if( y % 100 == 0 )
						Log.d(TAG, "thread " + this.getId() + ": process: " + (y - y_start) + " / " + (y_stop - y_start));
				}
				// read out this row for each bitmap
				for(int i=0;i<n_bitmaps;i++) {
					bitmaps.get(i).getPixels(buffers[i], 0, bm.getWidth(), 0, y, bm.getWidth(), 1);
				}
				for(int x=0;x<bm.getWidth();x++) {
					//int this_col = buffer[c];
					calculateHDR(hdr, n_bitmaps, buffers, x, response_functions);
					tonemap(rgb, hdr, avg_luminance);
					/*{
						// check
						if( new_r < 0 || new_r > 255 )
							throw new RuntimeException();
						else if( new_g < 0 || new_g > 255 )
							throw new RuntimeException();
						else if( new_b < 0 || new_b > 255 )
							throw new RuntimeException();
					}*/
					int new_col = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
					buffers[0][x] = new_col;
				}
				bm.setPixels(buffers[0], 0, bm.getWidth(), 0, y, bm.getWidth(), 1);
			}
		}
	}
	
	/** Core implementation of HDR algorithm.
	 */
	private void processHDRCore(List<Bitmap> bitmaps) {
		if( MyDebug.LOG )
			Log.d(TAG, "processHDRCore");
		
    	long time_s = System.currentTimeMillis();
		
		int n_bitmaps = bitmaps.size();
		Bitmap bm = bitmaps.get(0);
		final int base_bitmap = 1; // index of the bitmap with the base exposure
		ResponseFunction [] response_functions = new ResponseFunction[n_bitmaps]; // ResponseFunction for each image (the ResponseFunction entry can be left null to indicate the Identity)
		int [][] buffers = new int[n_bitmaps][];
		for(int i=0;i<n_bitmaps;i++) {
			buffers[i] = new int[bm.getWidth()];
		}
		float [] hdr = new float[3];
		//int [] rgb = new int[3];
		
		// compute response_functions
		for(int i=0;i<n_bitmaps;i++) {
			ResponseFunction function = null;
			if( i != base_bitmap ) {
				function = createFunctionFromBitmaps(i, bitmaps.get(i), bitmaps.get(base_bitmap));
			}
			response_functions[i] = function;
		}
		
		// calculate average luminance by sampling
		final int n_samples_c = 100;
		final int n_w_samples = (int)Math.sqrt(n_samples_c);
		final int n_h_samples = n_samples_c/n_w_samples;

		double sum_log_luminance = 0.0;
		int count = 0;
		for(int y=0;y<n_h_samples;y++) {
			double alpha = ((double)y+1.0) / ((double)n_h_samples+1.0);
			int y_coord = (int)(alpha * bm.getHeight());
			for(int i=0;i<n_bitmaps;i++) {
				bitmaps.get(i).getPixels(buffers[i], 0, bm.getWidth(), 0, y_coord, bm.getWidth(), 1);
			}
			for(int x=0;x<n_w_samples;x++) {
				double beta = ((double)x+1.0) / ((double)n_w_samples+1.0);
				int x_coord = (int)(beta * bm.getWidth());
				if( MyDebug.LOG )
					Log.d(TAG, "sample luminance from " + x_coord + " , " + y_coord);
				calculateHDR(hdr, n_bitmaps, buffers, x_coord, response_functions);
				double luminance = calculateLuminance(hdr[0], hdr[1], hdr[2]) + 1.0; // add 1 so we don't take log of 0..;
				sum_log_luminance += Math.log(luminance);
				count++;
			}
		}
		float avg_luminance = (float)(Math.exp( sum_log_luminance / count ));
		if( MyDebug.LOG )
			Log.d(TAG, "avg_luminance: " + avg_luminance);

		// write new hdr image
		final int n_threads =  Runtime.getRuntime().availableProcessors();
		if( MyDebug.LOG )
			Log.d(TAG, "create n_threads: " + n_threads);
		// create threads
		HDRWriterThread [] threads = new HDRWriterThread[n_threads];
		for(int i=0;i<n_threads;i++) {
			int y_start = (i*bm.getHeight()) / n_threads;
			int y_stop = ((i+1)*bm.getHeight()) / n_threads;
			threads[i] = new HDRWriterThread(y_start, y_stop, bitmaps, response_functions, avg_luminance);
		}
		// start threads
		if( MyDebug.LOG )
			Log.d(TAG, "start threads");
		for(int i=0;i<n_threads;i++) {
			threads[i].start();
		}
		// wait for threads to complete
		if( MyDebug.LOG )
			Log.d(TAG, "wait for threads to complete");
		try {
			for(int i=0;i<n_threads;i++) {
				threads[i].join();
			}
		}
		catch(InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if( MyDebug.LOG )
			Log.d(TAG, "time for processHDRCore: " + (System.currentTimeMillis() - time_s));
	}
	
	final float weight_scale_c = (float)((1.0-1.0/127.5)/127.5);

	private void calculateHDR(float [] hdr, int n_bitmaps, int [][] buffers, int x, ResponseFunction [] response_functions) {
		float hdr_r = 0.0f, hdr_g = 0.0f, hdr_b = 0.0f;
		float sum_weight = 0.0f;
		for(int i=0;i<n_bitmaps;i++) {
			int color = buffers[i][x];
			float r = (float)((color & 0xFF0000) >> 16);
			float g = (float)((color & 0xFF00) >> 8);
			float b = (float)(color & 0xFF);
			float avg = (r+g+b) / 3.0f;
			// weight_scale_c chosen so that 0 and 255 map to a non-zero weight of 1.0/127.5
			float weight = 1.0f - weight_scale_c * Math.abs( 127.5f - avg );
			//double weight = 1.0;
			/*if( MyDebug.LOG && x == 1547 && y == 1547 )
				Log.d(TAG, "" + x + "," + y + ":" + i + ":" + r + "," + g + "," + b + " weight: " + weight);*/
			if( response_functions[i] != null ) {
				// faster to access the parameter directly
				float parameter = response_functions[i].parameter;
				r *= parameter;
				g *= parameter;
				b *= parameter;
			}
			hdr_r += weight * r;
			hdr_g += weight * g;
			hdr_b += weight * b;
			sum_weight += weight;
		}
		hdr_r /= sum_weight;
		hdr_g /= sum_weight;
		hdr_b /= sum_weight;
		hdr[0] = hdr_r;
		hdr[1] = hdr_g;
		hdr[2] = hdr_b;
	}

	/* Initial test implementation - for now just doing an average, rather than HDR.
	 */
	private void processHDRAverage(List<Bitmap> bitmaps) {
		if( MyDebug.LOG )
			Log.d(TAG, "processHDRAverage");
    	long time_s = System.currentTimeMillis();
		
		Bitmap bm = bitmaps.get(0);
		int n_bitmaps = bitmaps.size();
		int [] total_r = new int[bm.getWidth()*bm.getHeight()];
		int [] total_g = new int[bm.getWidth()*bm.getHeight()];
		int [] total_b = new int[bm.getWidth()*bm.getHeight()];
		for(int i=0;i<bm.getWidth()*bm.getHeight();i++) {
			total_r[i] = 0;
			total_g[i] = 0;
			total_b[i] = 0;
		}
		//int [] buffer = new int[bm.getWidth()*bm.getHeight()];
		int [] buffer = new int[bm.getWidth()];
		for(int i=0;i<n_bitmaps;i++) {
			//bitmaps.get(i).getPixels(buffer, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
			for(int y=0,c=0;y<bm.getHeight();y++) {
				if( MyDebug.LOG ) {
					if( y % 100 == 0 )
						Log.d(TAG, "process " + i + ": " + y + " / " + bm.getHeight());
				}
				bitmaps.get(i).getPixels(buffer, 0, bm.getWidth(), 0, y, bm.getWidth(), 1);
				for(int x=0;x<bm.getWidth();x++,c++) {
					//int this_col = buffer[c];
					int this_col = buffer[x];
					total_r[c] += this_col & 0xFF0000;
					total_g[c] += this_col & 0xFF00;
					total_b[c] += this_col & 0xFF;
				}
			}
		}
		if( MyDebug.LOG )
			Log.d(TAG, "time before write: " + (System.currentTimeMillis() - time_s));
		// write:
		for(int y=0,c=0;y<bm.getHeight();y++) {
			if( MyDebug.LOG ) {
				if( y % 100 == 0 )
					Log.d(TAG, "write: " + y + " / " + bm.getHeight());
			}
			for(int x=0;x<bm.getWidth();x++,c++) {
				total_r[c] /= n_bitmaps;
				total_g[c] /= n_bitmaps;
				total_b[c] /= n_bitmaps;
				//int col = Color.rgb(total_r[c] >> 16, total_g[c] >> 8, total_b[c]);
				int col = (total_r[c] & 0xFF0000) | (total_g[c] & 0xFF00) | total_b[c];
				buffer[x] = col;
			}
			bm.setPixels(buffer, 0, bm.getWidth(), 0, y, bm.getWidth(), 1);
		}

		if( MyDebug.LOG )
			Log.d(TAG, "time for processHDRAverage: " + (System.currentTimeMillis() - time_s));
	}
}