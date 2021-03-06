package fi.helsinki.cs.bsmr.master;

/**
 * The MIT License
 * 
 * Copyright (c) 2010   Department of Computer Science, University of Helsinki
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * Author Sampo Savolainen
 *
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The container for messages sent between workers and the master.
 * 
 * @author stsavola
 * 
 */
public class Message
{
private static final Logger logger = Util.getLoggerForClass(Message.class);

public enum Type
{
DO, ACK, HB
}

public enum Action
{
mapSplit, reduceBucket, reduceChunk, idle
}

public static final String FIELD_ACTION = "action";

public static final String FIELD_PAYLOAD = "payload";
public static final String FIELD_TYPE = "type";
public static final String FIELD_JOBID = "jobId";
public static final String FIELD_JOB_MAP = "job";

public static final String FIELD_MAPSTATUS = "mapStatus";
public static final String FIELD_REDUCESTATUS = "reduceStatus";
public static final String FIELD_UNREACHABLE = "unreachable";

public static final String FIELD_NUM_REDUCETASKS = "R";
public static final String FIELD_NUM_MAPTASKS = "M";

public static final String FIELD_SPLITID = "splitId";
public static final String FIELD_BUCKETID = "bucketId";
public static final String FIELD_REDUCE_LOCATION = "locations";

public static final String FIELD_CODE = "code";

public static final String FIELD_INTERURL = "interUrl";

private Type type;
private Action action;

private Job job;

private MapStatus mapStatus;
private ReduceStatus reduceStatus;
private Set<Worker> unreachableWorkers;
private String interUrl = null;

private Message(Type t, Action a)
	{
	this.type = t;
	this.action = a;

	this.job = null;
	this.mapStatus = null;
	this.reduceStatus = null;
	this.unreachableWorkers = null;
	}

private Message(Type t, Action a, Job j)
	{
	this.type = t;
	this.action = a;
	this.job = j;

	this.mapStatus = null;
	this.reduceStatus = null;
	this.unreachableWorkers = null;
	}

private Message(Map<Object, Object> d, MasterContext master, String remoteAddr)
		throws IllegalMessageException
	{
	Map<?, ?> payload = (Map<?, ?>) d.get(FIELD_PAYLOAD);
	if (payload == null)
		{
		throw new IllegalMessageException("No payload");
		}

	String typeField = (String) d.get(FIELD_TYPE);
	try
		{
		this.type = Type.valueOf(typeField);
		}
	catch (NullPointerException npe)
		{
		throw new IllegalMessageException("No type field");
		}
	catch (IllegalArgumentException iae)
		{
		throw new IllegalMessageException("Message type '" + typeField
				+ "' not recognized");
		}

	String actionField = (String) payload.get(FIELD_ACTION);
	try
		{
		this.action = Action.valueOf(actionField);
		}
	catch (NullPointerException npe)
		{
		throw new IllegalMessageException("No action field in payload");
		}
	catch (IllegalArgumentException iae)
		{
		throw new IllegalMessageException("Message action '" + actionField
				+ "' not recognized");
		}

	if (payload.containsKey(FIELD_JOBID))
		{
		Object o = payload.get(FIELD_JOBID);
		if (o != null)
			{
			int jobNum = Util.getIntFromJSONObject(o);
			this.job = master.getJobById(jobNum);
			}

		}
	
	if (payload.containsKey(FIELD_INTERURL))
		{
		Object o = payload.get(FIELD_INTERURL);
		if (o != null)
			{
			this.interUrl = (String)o;
			}
		}

	//remoteAddr is the detected ip address of the worker, and could later be
	//filled in the interUrl
	this.mapStatus = createMapStatus((Map<?, ?>) payload.get(FIELD_MAPSTATUS));
	this.reduceStatus = createReduceStatus((Map<?, ?>) payload
			.get(FIELD_REDUCESTATUS));

	this.unreachableWorkers = parseWorkers(payload.get(FIELD_UNREACHABLE),
			master);
	//Set<Worker> urlless = null;
	//this.unreachableWorkers.addAll(urlless);
	}

@SuppressWarnings({ "unchecked", "rawtypes" })
private static Set<Worker> parseWorkers(Object workersAsUrls,
		MasterContext workers)
	{
	if (workersAsUrls == null)
		return null;

	Collection<Object> set;
	if (workersAsUrls instanceof Collection)
		{
		set = (Collection<Object>) workersAsUrls;
		}
	else if (workersAsUrls instanceof Map)
		{
		logger
				.fine("Parsing workers from message using a map?? Using the keys");
		set = ((Map) workersAsUrls).keySet();
		}
	else
		{
		try
			{
			Object[] tmp = (Object[]) workersAsUrls;
			set = new ArrayList<Object>();
			for (Object o : tmp)
				{
				set.add(o);
				}
			}
		catch (ClassCastException cce)
			{
			if (workersAsUrls != null)
				{
				logger.severe("Unable to use workersAsUrls parameter: "
						+ workersAsUrls);
				}
			return Collections.emptySet();
			}

		}

	Set<Worker> ret = new HashSet<Worker>();

	for (Object o : set)
		{
		if (o != null && !(o instanceof String))
			{
			logger.severe("payload contains non-string worker URLs");
			continue;
			}
		String url = (String) o;
		Worker w = workers.getWorkerByURL(url);
		if (w != null)
			{
			ret.add(w);
			}
		}

	return ret;
	}

public Type getType()
	{
	return type;
	}

public Action getAction()
	{
	return action;
	}

public Job getJob()
	{
	return job;
	}

public MapStatus getMapStatus()
	{
	return mapStatus;
	}

public ReduceStatus getReduceStatus()
	{
	return reduceStatus;
	}

/**
 * Parse a JSON message to the internal representation.
 * 
 * @param msg
 *            Message as string
 * @param master
 *            The master the worker for whom the message came for belongs to
 * @param remoteAddr
 *            The remote address of the worker
 * @return The parsed message
 * @throws IllegalMessageException
 *             If there is a syntax error in the message
 */
@SuppressWarnings("unchecked")
public static Message parseMessage(String msg, MasterContext master,
		String remoteAddr) throws IllegalMessageException
	{
	Map<Object, Object> tmp = (Map<Object, Object>) JSON.parse(msg);
	try
		{
		return new Message(tmp, master, remoteAddr);
		}
	catch (IllegalMessageException ime)
		{
		ime.setProblematicMessage(msg);
		throw ime;
		}
	}

/**
 * Construct a JSON string for this message. This method needs to be called
 * within the "big lock" as concurrent modifications to the current Job might
 * cause the encoding to fail.
 * 
 * @return This message encoded into a JSON string
 */
public String encodeMessage()
	{
	Map<Object, Object> data = new HashMap<Object, Object>();
	Map<Object, Object> payload = new HashMap<Object, Object>();

	data.put(FIELD_TYPE, type.toString());
	data.put(FIELD_PAYLOAD, payload);

	payload.put(FIELD_ACTION, action.toString());
	if (reduceStatus != null)
		{
		payload.put(FIELD_REDUCESTATUS, reduceStatus.asMap());
		}
	if (mapStatus != null)
		{
		payload.put(FIELD_MAPSTATUS, mapStatus.asMap());
		}

	if (job != null)
		{
		Map<Object, Object> jobMap = getJSONMapForJob(job);

		payload.put(FIELD_JOB_MAP, jobMap);
		}

	return JSON.toString(data);
	}

/**
 * A helper method to create a rudimentary Map object for a Job. The Map is
 * supposed to be used for JSON conversion.
 * 
 * @param job
 *            The job to create a map for
 * @return The map
 */
public static Map<Object, Object> getJSONMapForJob(Job job)
	{
	Map<Object, Object> jobMap = new HashMap<Object, Object>();
	jobMap.put(FIELD_JOBID, job.getJobId());
	jobMap.put(FIELD_NUM_MAPTASKS, job.getMapTasks());
	jobMap.put(FIELD_NUM_REDUCETASKS, job.getReduceTasks());
	jobMap.put(FIELD_CODE, job.getCode());
	return jobMap;
	}

/**
 * A synonym for encodeMessage()
 * 
 * @see Message#encodeMessage()
 */
public String toString()
	{
	return encodeMessage();
	}

/**
 * Create a job-agnostic idle message
 * 
 * @return A message instructing a worker to idle
 */
public static Message pauseMessage()
	{
	return new Message(Type.DO, Action.idle);
	}

/**
 * Create a message instructing a worker to map a split.
 * 
 * @param s
 *            The split to map
 * @param j
 *            The job for which this task is for
 * @return The message
 */
public static Message mapThisMessage(Split s, Job j)
	{
	Message ret = new Message(Type.DO, Action.mapSplit, j);
	ret.mapStatus = ret.new MapStatus(s);
	return ret;
	}

/**
 * Create a message instructing a worker to reduce a bucket.
 * 
 * @param b
 *            The bucket to reduce
 * @param j
 *            The job for which this task is for
 * @return The message
 */
public static Message reduceThatMessage(Bucket b, Job j)
	{
	Message ret = new Message(Type.DO, Action.reduceBucket, j);
	ret.reduceStatus = ret.new ReduceStatus(b, null, null);
	return ret;
	}

/**
 * Create a message instructing a worker where to find certain chunk at.
 * 
 * @param b
 *            The bucket the worker is currently reducing
 * @param s
 *            For which split the message contains worker socket URLs for
 * @param j
 *            The job for which this task is for
 * @param unreachableWorkers
 *            A list of workers who are unreachable to the target of this
 *            message. The message will not contain URLs for these workers.
 * @return The message
 */
public static Message findChunkAtMessage(Bucket b, Split s, Job j,
		Set<Worker> unreachableWorkers)
	{
	Message ret = new Message(Type.DO, Action.reduceChunk, j);
	Set<Worker> hasSplit = new HashSet<Worker>();

	for (Worker w : j.getSplitInformation().canProvideSplit(s))
		{

		if (unreachableWorkers.contains(w) || w.getSocketURL() == null)
			continue;

		hasSplit.add(w);
		}

	ret.reduceStatus = ret.new ReduceStatus(b, s, hasSplit);
	return ret;
	}

public Set<Worker> getUnareachableWorkers()
	{
	if (unreachableWorkers == null)
		return Collections.emptySet();
	return unreachableWorkers;
	}

public String getInterUrl()
	{
	return interUrl;
	}

/**
 * Return which bucket the worker is reducing. This is used when the worker
 * has sent a reduceSplit message.
 * 
 * @return The bucket the worker is currently reducing.
 */
public Bucket getIncompleteReduceBucket()
	{
	return reduceStatus.bucket;
	}

public class MapStatus
{
public MapStatus(Split s)
	{
	this.split = s;
	}

public Map<Object, Object> asMap()
	{
	Map<Object, Object> ret = new HashMap<Object, Object>();
	ret.put(FIELD_SPLITID, split.getId());
	return ret;
	}

Split split;
}

public MapStatus createMapStatus(Map<?, ?> map)
	{
	if (map == null)
		return null;

	Object o = map.get(FIELD_SPLITID);
	if (o == null)
		return null;

	Split s = new Split(Util.getIntFromJSONObject(o));
	return new MapStatus(s);
	}

public class ReduceStatus
{
public ReduceStatus(Bucket p, Split s, Set<Worker> l)
	{
	this.bucket = p;
	this.split = s;
	this.location = l;
	}

public Map<Object, Object> asMap()
	{
	Map<Object, Object> ret = new HashMap<Object, Object>();

	ret.put(FIELD_BUCKETID, bucket.getId());
	if (split != null)
		ret.put(FIELD_SPLITID, split.getId());

	if (location != null)
		{
		Set<String> tmp = new HashSet<String>();
		for (Worker w : location)
			{
			tmp.add(w.getSocketURL());
			}
		ret.put(FIELD_REDUCE_LOCATION, tmp);
		}

	return ret;
	}

Bucket bucket;
Split split;
Set<Worker> location;
}

public ReduceStatus createReduceStatus(Map<?, ?> map)
	{
	if (map == null)
		return null;

	Split s = null;
	Bucket p = null;

	Object o1 = map.get(FIELD_SPLITID);
	Object o2 = map.get(FIELD_BUCKETID);

	if (o1 != null)
		s = new Split(Util.getIntFromJSONObject(o1));
	if (o2 != null)
		p = new Bucket(Util.getIntFromJSONObject(o2));

	// TODO: no locations in ACK messsages

	return new ReduceStatus(p, s, null);
	}
}
