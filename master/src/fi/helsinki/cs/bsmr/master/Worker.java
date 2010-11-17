package fi.helsinki.cs.bsmr.master;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.websocket.WebSocket;

import fi.helsinki.cs.bsmr.master.Message.Action;
import fi.helsinki.cs.bsmr.master.Message.Type;

public class Worker implements WebSocket 
{
	private static Logger logger = Util.getLoggerForClass(Worker.class);
	
	private Outbound out;
	private Master master;
	
	private long lastHearbeat;
	private long lastProgress;
	
	private String socketURL;
	
	
	Worker(Master master)
	{
		this.master = master;
		
		// Before worker starts communicating, it should be "dead":
		this.lastHearbeat = -1;
		this.lastProgress = -1;
	}
	

	public void disconnect() 
	{
		// Make sure nobody relies on this worker anymore
		lastHearbeat = -1;
		lastProgress = -1;
		
		out.disconnect();
	}

	/** WebSocket callback implementations **/
	
	@Override
	public void onConnect(Outbound out) 
	{
		TimeContext.markTime();
		
		logger.fine("onConnect()");
		this.out = out;

		// TODO: is this best here, or should we wait for the UP message?
		this.lastHearbeat = this.lastProgress = TimeContext.now();
		
		try {
			master.addWorker(this);
		} catch(WorkerInIllegalStateException wiise) {
			logger.log(Level.SEVERE, "New worker already registered?!?", wiise);
			disconnect();
		}
	}

	@Override
	public void onDisconnect()
	{
		TimeContext.markTime();
		
		// Make sure nobody relies on this worker anymore
		lastHearbeat = -1;
		lastProgress = -1;
		
		logger.fine("onDisconnect()");
		
		try { 
			master.removeWorker(this);
		} catch(WorkerInIllegalStateException wiise) {
			logger.log(Level.SEVERE, "Disconnected worker not registered?!?", wiise);
		}
		
	}


	/**
	 * Division of labor between Worker.onMessage() and Master.executeWorkerMessage() is 
	 * still a bit unclear. Current attempt is: worker level functionality (e.g. heart beat)
	 * while master provides access to the current work. However making sure the worker
	 * uses the correct job id needs to be in Worker.onMessage() as one needs to react
	 * before parsing the heart beat.
	 * 
	 * Note that locking which usually starts in the master is started here to avoid
	 * concurrency issues with switching jobs. 
	 * 
	 * 
	 */
	@Override
	public void onMessage(byte frame, String jsonMsg) 
	{
		TimeContext.markTime();
		
		if (logger.isLoggable(Level.FINE)) {
			logger.finest( "onMessage(): '"+jsonMsg+"' (frame "+frame+")");
		}	
		
		Message msg = Message.parseMessage(jsonMsg, master);
		
		if (msg.getType() == Type.DO) {
			logger.severe("Workers cannot send DO messages: "+msg);
			return;
		}
		
		Message reply;
		
		synchronized (master.executeLock) {

			if (msg.getAction() == Message.Action.socket) {
				setSocketURL(msg.getSocketURL());
			}
			
			if (master.getActiveJob() == null || master.getActiveJob().isFinished() || !master.getActiveJob().isStarted()) {
				
				if (msg.getType() == Type.HB) {
					logger.warning("A worker sent a non-heartbeat while no active job");
					// TODO: send new idle?
					reply = null; // set to idle message
				} else {
					reply = null;
				}
				
				lastHearbeat = TimeContext.now();
				lastProgress = TimeContext.now();
				
				
				
			} else {
				
				// The heart beat is always updated
				lastHearbeat = TimeContext.now();
				
				if (msg.getType() == Type.HB) {
					// If just a pure heart beat, we do not reply
					logger.fine("heartbeat");
					return;
				}

				lastProgress = TimeContext.now();
				
				
				
				// TODO: parse a "where is worker message"?
				
				reply = master.executeWorkerMessage(this, msg);
				
				
			}
		}
		
		if (reply != null) {
			try {
				out.sendMessage(reply.encodeMessage());
			} catch(IOException ie) {
				logger.log(Level.SEVERE, "Could not reply to worker. Terminating connection.", ie);
				
				try {
					master.removeWorker(this);
				} catch(WorkerInIllegalStateException wiise) {
					logger.log(Level.SEVERE, "The worker we could not send a reply to was not registered as a worker?", wiise);
				} finally {
					disconnect();
				}
			}
		}

	}

	@Override
	public void onMessage(byte arg0, byte[] arg1, int arg2, int arg3)
	{
		TimeContext.markTime();
		logger.severe("onMessage() byte format unsupported!");
		throw new RuntimeException("onMessage() byte format unsupported!");
	}


	public boolean isAvailable(Job job)
	{
		if (isDead(job)) return false;
		return ( (TimeContext.now() - lastHearbeat)    < job.getWorkerHeartbeatTimeout());
	}
	

	public boolean isDead(Job job)
	{
		return ( (TimeContext.now() - lastProgress) > job.getWorkerAcknowledgeTimeout());
	}

	public void setSocketURL(String url)
	{
		socketURL = url;
	}
	
	public String getSocketURL()
	{
		return socketURL;
	}
	
}
