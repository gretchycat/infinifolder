/*
** AlacContext.java
**
** Copyright (c) 2011 Peter McQuillan
**
** All Rights Reserved.
**                       
** Distributed under the BSD Software License (see license.txt)  
**
*/

package com.beatofthedrum.alacdecoder;

import java.io.Closeable;
import java.io.IOException;

public class AlacContext implements Closeable
{
	public DemuxResT demux_res = new DemuxResT();
	public AlacFile alac = new AlacFile();
	public AlacInputStream input_stream;
	public int current_sample_block = 0;
	public int offset;
	public boolean error;
	public String error_message = "";
	public byte[] read_buffer = new byte[1024 * 80]; // sample big enough to hold any input for a single alac frame

	@Override
	public void close() throws IOException {
		if (input_stream != null) {
			input_stream.close();
		}
	}
}
