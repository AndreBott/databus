package controllers.modules2;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gov.nrel.util.TimeValue;

import org.apache.commons.collections.buffer.CircularFifoBuffer;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.astyanax.connectionpool.exceptions.BadRequestException;

import play.mvc.results.BadRequest;
import controllers.modules.SplinesBigDec;
import controllers.modules.SplinesBigDecBasic;
import controllers.modules.SplinesBigDecLimitDerivative;
import controllers.modules2.framework.ProcessedFlag;
import controllers.modules2.framework.ReadResult;
import controllers.modules2.framework.TSRelational;
import controllers.modules2.framework.VisitorInfo;
import controllers.modules2.framework.procs.ProcessorSetup;
import controllers.modules2.framework.procs.PullProcessor;
import controllers.modules2.framework.procs.PullProcessorAbstract;

public class SplinesV3PullProcessor extends PullProcessorAbstract {

	private static final Logger log = LoggerFactory.getLogger(SplinesPullProcessor.class);
	private String splineType;
	private long epochOffset;
	protected long interval;
	private List<String> columnsToInterpolate;

	private List<ColumnState> columns = new ArrayList<ColumnState>();
	private UninterpalatedValueMethod uninterpalatedValueMethod = UninterpalatedValueMethod.NEAREST_ROW;
	private long currentTimePointer;
	private ReadResult lastValue;

	@Override
	protected int getNumParams() {
		return 0;
	}

	@Override
	public String init(String path, ProcessorSetup nextInChain, VisitorInfo visitor, HashMap<String, String> options) {
		if(log.isInfoEnabled())
			log.info("initialization of splines pull processor");
		String newPath = super.init(path, nextInChain, visitor, options);
		columnsToInterpolate=Arrays.asList(new String[]{valueColumn});
		String columnsToInterpolateString = options.get("columnsToInterpolate");
		if (StringUtils.isNotBlank(columnsToInterpolateString)) {
			columnsToInterpolate = Arrays.asList(StringUtils.split(columnsToInterpolateString, ";"));
		}
		String uninterpalatedValueMethodString = options.get("uninterpalatedValueMethod");
		if (StringUtils.equalsIgnoreCase("previous", uninterpalatedValueMethodString))
			uninterpalatedValueMethod = UninterpalatedValueMethod.PREVIOUS_ROW;

		// param 2: Interval: long
		String intervalStr = fetchProperty("interval", "60000", options);
		try {
			interval = Long.parseLong(intervalStr);
			if (interval < 1) {
				String msg = "/splinesV2(interval="+interval+")/ ; interval must be > 0 ";
				throw new BadRequest(msg);
			}
		} catch (NumberFormatException e) {
			String msg = "/splinesV3(interval="+intervalStr+")/ ; interval is not a long ";
			throw new BadRequest(msg);
		}

		String epoch = options.get("epochOffset");
		if(epoch == null) {
			epochOffset = calculateOffset();
		} else
			epochOffset = parseOffset(epoch);

		if(params.getStart() == null || params.getEnd() == null) {
			String msg = "splinesV3 must have a start and end (if you want it to work, request it)";
			throw new BadRequest(msg);
		}

		Long startTime = params.getStart();
		if(log.isInfoEnabled())
			log.info("offset="+epochOffset+" start="+startTime+" interval="+interval);
		currentTimePointer = calculateStartTime(startTime, interval, epochOffset);

		String multipleOfInterval = fetchProperty("maxToStopSplining", "5", options);
		int maxNumIntervalsStopSplining = Integer.parseInt(multipleOfInterval);
		long maxTimeToStopSplining = interval * maxNumIntervalsStopSplining;

		String bufferSizeStr = fetchProperty("bufferSize", "20", options);
		int bufferSize = Integer.parseInt(bufferSizeStr);
		if(bufferSize >= 1000)
			throw new BadRequest("bufferSize is too large.  must be less than 1000.  size="+bufferSize);
		else if(bufferSize < 0)
			throw new BadRequest("bufferSize is too small. must be 0 or greater. size="+ bufferSize);
		//USE CASES
		//#1 What if one columns buffer is A1,A2,A3,NNNNNNN
		//#2 What is one columns buffer is that of #1 but with an A4 that is 1000 rows away(we don't want to read all the data in)
		//#3 
		//use windowFilterSize as max gap as well seems a bit wrong?  Have maxgap variable with default at 10 intervals

		//1    2    3    4    5   6    7    8
		//A1   A2   A3   N    N   N    N    A4
		//B1   B2   B3   B4   N   B5   B6   B7
		//
		//
		//125         890
		//1256        78N0
		//1234        567890
		//1234567890
		//
		//125
		//1256
		//2345  then 3456

		splineType = fetchProperty("splineType", "basic", options);

		/**
		 * Current spline options: basic -> SplinesBigDecBasic limitderivative
		 * -> SplinesBigDecLimitDerivative
		 */
		if ("basic".equals(splineType)) {
			for (String colname:columnsToInterpolate)
				columns.add(new ColumnState(new SplinesBigDecBasic(), timeColumn, colname, bufferSize, maxTimeToStopSplining));
		} else if ("limitderivative".equals(splineType)) {
			for (String colname:columnsToInterpolate)
				columns.add(new ColumnState(new SplinesBigDecLimitDerivative(), timeColumn, colname, bufferSize, maxTimeToStopSplining));
		} else {
			// fix this bad request line
			String msg = "/splinesV3(type="+splineType+")/ ; type must be basic or limitderivative";
			throw new BadRequest(msg);
		}

		return newPath;
	}

	protected long calculateOffset() {
		Long startTime = params.getStart();
		long offset = startTime % interval;
		return offset;
	}

	protected long parseOffset(String offset) {
		try {
			return Long.parseLong(offset);
		} catch (NumberFormatException e) {
			String msg = "/splinesV3(epochOffset="+offset+")/ epochOffset is not a long";
			throw new BadRequest(msg);
		}
	}

	private String fetchProperty(String key, String defaultVal, HashMap<String, String> options) {
		String s = options.get(key);
		if(s != null)
			return s;
		return defaultVal;
	}

	public static long calculateStartTime(long startTime, long interval, Long epochOffset) {
		if(epochOffset == null)
			return startTime;

		long rangeFromOffsetToStart = startTime-epochOffset;
		long offsetFromStart = -rangeFromOffsetToStart % interval;
		if(startTime > 0) {
			offsetFromStart = interval - (rangeFromOffsetToStart%interval);
		}

		long result = startTime+offsetFromStart; 
		if(log.isInfoEnabled())
			log.info("range="+rangeFromOffsetToStart+" offsetFromStart="+offsetFromStart+" startTime="+startTime+" result="+result);
		return result;
	}

	@Override
	public ReadResult read() {
		//we are ready as soon as one of the streams has enough data in the buffer(ie. 4 points needs for a spline)
		//the other streams will have to return null until they have enough data points is all.
		while(!anyStreamIsReady()) {
			pull();
			if (lastValue == null) {
				return null;
			} else if (lastValue.isMissingData()) {
				return lastValue;
			} else if (lastValue.isEndOfStream()) {
				return calculateLastRows(lastValue);
			} else {
				transferRow(lastValue.getRow());
			}
		}

		//FIRST is move currentTimePointer so currentTime > 2nd point time(of 4 point spline)
		//We need at least one of the streams to have currentTimePointer after the 2nd point so can spline that
		//ONE guy at that time point(and the other ones would have to just return null
//		if(currentTimeLessThanAll2ndTimePoints(currentTimePointer))
//		if(all2ndTimePointsLargerThan(currentTimePointer)) {
//			TSRelational row = new TSRelational();
//			setTime(row, currentTimePointer);
//			for(ColumnState s: columns) {
//				row.put(s.getColumn(), null);
//			}
//			currentTimePointer += interval;
//			return new ReadResult(getUrl(), row);
//		}

		//needMoreData is a very tricky method so read the comments in that method
		long end = params.getEnd();
		while(needMoreData() && currentTimePointer <= end) {
			pull();
			if (lastValue == null) {
				return null;
			} else if (lastValue.isMissingData()) {
				return lastValue;
			} else if (lastValue.isEndOfStream()) {
				return calculateLastRows(lastValue);
			} else {
				transferRow(lastValue.getRow());
			}			
		}

		//NOW, we are in a state where AT LEAST one column has 2nd timept < currentTime < 3rd timept so we can spline
		//for that stream(hopefully this is true for a few of the columns)
		ReadResult returnVal = null;
		if (currentTimePointer <= end) {
			returnVal = calculate();
		} else {
			//signify end of stream
			returnVal = new ReadResult();
		}

		return returnVal;
	}

	private ReadResult calculateLastRows(ReadResult lastValue) {
		long end = params.getEnd();
		if(currentTimePointer > end) {
			return lastValue;
		}
		
		//otherwise we still need to calculate the splines
		for(ColumnState s : columns) {
			s.prepareBuffer(currentTimePointer);
		}
		return calculate();
	}

	private boolean all2ndTimePointsLargerThan(long currentTimePointer2) {
		for(ColumnState s : columns) {
			if(!s.secondPointGreaterThan(currentTimePointer2))
				return false; //If anyone viol
		}
		return true;
	}

	private boolean needMoreData() {
		//1st, if currentTimePoint is less than 3rd timepoint for EVERY column, we don't need more data(
		//2nd, if currentTimePoint is is between 2nd and 3rd on stream A BUT before 2nd on stream B, we need more data ONLY IF
		//          stream A does not have a full buffer
		//if case2 is stream A has a full buffer and currentTime < stream B 2nd point, then stream B will not be able to do a 
		//spline and will have to return null, otherwise pulling more data just puts more in buffer(leftOver) for stream A while adding to
		//the spline buffer of stream B(instead of leftOver buffer)

		//CASE #1....
		Set<ColumnState> columnThatNeedsMore = new HashSet<ColumnState>();
		Set<ColumnState> columnsNotNeedingMore = new HashSet<ColumnState>();
		for(ColumnState s : columns) {
			//IF we can't spline with ANY SINGLE stream(needMore=true), we need to move on and check use CASE #2 below
			if(s.needMoreData(currentTimePointer))
				columnThatNeedsMore.add(s);
			else
				columnsNotNeedingMore.add(s);
		}
		//if 
		if(columnThatNeedsMore.size() == 0)
			return false; //no columns need more data, yeah!!!
		else if(columnThatNeedsMore.size() == columns.size())
			return true; //all columns need more data, yeah!!!! (easy case)

		//At this point, we have N columns that need more data
		//Use CASE #2.  we need more data ONLY IF stream A, B, C does not have a full buffer 
		for(ColumnState s : columnsNotNeedingMore) {
			//If any of the columns NOT needing data have a full leftover buffer, we have to wait on those
			//columns leftOver buffer to shrink before we need more rows to be read in..
			if(s.isLeftOverBufferFull())
				return false; 
		}

		return true;
	}

	private boolean anyStreamIsReady() {
		for(ColumnState s : columns) {
			if(s.getBuffer().isFull())
				return true;
		}
		//none of the streams are full...we only need one to be full to be ready
		return false;
	}

	private void transferRow(TSRelational row) {
		for(ColumnState s : columns) {
			s.transferRow(row);

			//I think we can have s.transferRow call prepareBuffer itself?
			s.prepareBuffer(currentTimePointer);
		}
	}

	private void pull() {
		PullProcessor ch = getChild();
		lastValue = ch.read();
	}



	private ReadResult calculate() {
		TSRelational row = new TSRelational();
		setTime(row, currentTimePointer);

		for(ColumnState s : columns) {
			s.calculate(row, currentTimePointer);
		}
		currentTimePointer += interval;
		return new ReadResult(getUrl(), row);
	}

	private enum UninterpalatedValueMethod {
		PREVIOUS_ROW,NEAREST_ROW
	}

	// TSRelational row = new TSRelational();
	// setValue(row, val);
	// setTime(row, val);
	// new ReadResult(getUrl(),row);
}
