/*
 * This code and all components (c) Copyright 2006 - 2018, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.wowza.util.StringUtils;
import com.wowza.util.SystemUtils;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.*;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.module.*;
import com.wowza.wms.stream.*;
import com.wowza.wms.stream.publish.Publisher;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHostSingleton;

public class ModuleDuplicateStreams extends ModuleBase
{
	// Listen for live packets on the source stream and propagate them to the target appInstance.
	private class SourcePacketListener implements IMediaStreamLivePacketNotify
	{

		@Override
		public void onLivePacket(IMediaStream stream, AMFPacket packet)
		{
			String streamName = stream.getName();
			synchronized(lock)
			{
				if(streamName.length() > 0 || (delayedPackets.containsKey(stream) && delayedPackets.get(stream).size() > DELAYED_MAX_SIZE))
				{
					if(delayedPackets.containsKey(stream))
					{
						if(delayedPackets.get(stream).size() > DELAYED_MAX_SIZE)
						{
							logger.warn(MODULE_NAME + ".onLivePacket stream name missing for too long.  Cannot publish stream.",  stream, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment, WMSLoggerIDs.STAT_publish_internal_error, null);
							delayedPackets.remove(stream);
							stream.removeLivePacketListener(this);
							if(doRestarts)
							{
								doRestart(streamName);
							}
							return;
						}
						flushDelayedPackets(stream);
					}
				}
				else
				{
					List<AMFPacket> packets = delayedPackets.get(stream);
					if(packets == null)
					{
						packets = new ArrayList<AMFPacket>();
						delayedPackets.put(stream, packets);
					}
					packets.add(packet);
					return;
				}
			}
			
			Publisher publisher = (Publisher)stream.getProperties().get(PROP_NAME_PREFIX + "Publisher");
			if(publisher == null)
			{
				boolean doStart = checkStartStream(streamName);
				if(!doStart)
				{
					stream.removeLivePacketListener(this);
					return;
				}
				
				IVHost vhost = VHostSingleton.getInstance(targetVHostName);
				
				String appName = targetAppName;
				String appInstName = IApplicationInstance.DEFAULT_APPINSTANCE_NAME;
				
				String[] context = targetAppName.split("/");
				if(context.length >= 1)
					appName = context[0];
				if(context.length >= 2)
					appInstName = context[1];
				
				publisher = Publisher.createInstance(vhost, appName, appInstName);
				
				if(publisher != null)
				{
					IMediaStream dstStream = publisher.getAppInstance().getStreams().getStream(streamName + streamNameSuffix);
					if(dstStream != null)
					{
						publisher.close();
						stream.removeLivePacketListener(this);
						logger.warn(MODULE_NAME + ".onLivePacket target stream already exists.  Cannot publish stream [source: " + streamName + ", target: " + streamName + streamNameSuffix + "]",  stream, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment, WMSLoggerIDs.STAT_publish_internal_error, null);
						if(doRestarts)
						{
							doRestart(streamName);
						}
						return;
					}
					
					publisher.setStreamType(publisher.getAppInstance().getStreamType());
					publisher.publish(streamName + streamNameSuffix);
					stream.getProperties().setProperty(PROP_NAME_PREFIX + "Publisher", publisher);
					publisher.getAppInstance().getApplication().addApplicationInstanceListener(targetAppInstanceNotify);
				}
			}

			switch (packet.getType())
			{
			case IVHost.CONTENTTYPE_AUDIO:
				publisher.addAudioData(packet.getData(), packet.getAbsTimecode());
				break;
				
			case IVHost.CONTENTTYPE_VIDEO:
				publisher.addVideoData(packet.getData(), packet.getAbsTimecode());
				break;
				
			case IVHost.CONTENTTYPE_DATA:
			case IVHost.CONTENTTYPE_DATA3:
				publisher.addDataData(packet.getData(), packet.getAbsTimecode());
			}
		}

		private boolean checkStartStream(String streamName)
		{
			if(!StringUtils.isEmpty(streamNameSuffix) && streamName.endsWith(streamNameSuffix))
				return false;
			boolean doStart = false;
			
			while(true)
			{
				if(streamNames.equals("*") || streamNames.equals(streamName))
				{
					doStart = true;
					break;
				}
				String[] names = streamNames.split(",");
				for(String name : names)
				{
					name = name.trim();
					if(name.equals(streamName))
					{
						doStart = true;
						break;
					}
					if(name.startsWith("*"))
					{
						if(streamName.endsWith(name.substring(1)))
						{
							doStart = true;
							break;
						}
					}
					if(name.endsWith("*"))
					{
						if(streamName.startsWith(name.substring(0, name.length() - 1)))
						{
							doStart = true;
							break;
						}
					}
				}
				break;
			}
			
			return doStart;
		}

		private void flushDelayedPackets(IMediaStream stream)
		{
			List<AMFPacket> packets = delayedPackets.remove(stream);
			for(AMFPacket packet : packets)
			{
				onLivePacket(stream, packet);
			}
		}
	}
	
	// Listen for metadata events so we can propagate them to the target stream.
	// Listen for unpublish notify events on the source stream so we can shut down the publisher that we started.
	private class SourceStreamNotify extends MediaStreamActionNotifyBase
	{

		@Override
		public void onMetaData(IMediaStream stream, AMFPacket packet)
		{
			sourcePacketListener.onLivePacket(stream, packet);
		}

		@Override
		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			synchronized(lock)
			{
				Publisher publisher = (Publisher)stream.getProperties().get(PROP_NAME_PREFIX + "Publisher");
				if (publisher != null)
				{
					publisher.unpublish();
					publisher.close();
					stream.getProperties().remove(PROP_NAME_PREFIX + "Publisher");
					stream.removeLivePacketListener(sourcePacketListener);
				}
			}
		}
	}
	
	// Listen for appInstance shutdown events for the the target appInstance indicating that it was shut down somehow and we should remove all publishers that we started.
	private class TargetAppInstanceNotify implements IApplicationInstanceNotify
	{

		@Override
		public void onApplicationInstanceCreate(IApplicationInstance targetAppInstance)
		{
			// no-op
		}

		@Override
		public void onApplicationInstanceDestroy(IApplicationInstance targetAppInstance)
		{
			synchronized(lock)
			{
				List<String> streamNames = appInstance.getPublishStreamNames();
				for(String streamName : streamNames)
				{
					IMediaStream stream = appInstance.getStreams().getStream(streamName);
					if(stream != null)
					{
						Publisher publisher  = (Publisher)stream.getProperties().get(PROP_NAME_PREFIX + "Publisher");
						if (publisher != null && publisher.getAppInstance().equals(targetAppInstance))
						{
							publisher.unpublish();
							publisher.close();
							stream.getProperties().remove(PROP_NAME_PREFIX + "Publisher");
							stream.removeLivePacketListener(sourcePacketListener);
							if(doRestarts)
							{
								doRestart(streamName);
							}
						}
					}
				}
			}
		}
	}
	
	class RestartTask extends TimerTask
	{
		private final String streamName;

		RestartTask(String streamName)
		{
			this.streamName = streamName;
		}

		@Override
		public void run()
		{
			IMediaStream stream = appInstance.getStreams().getStream(streamName);
			if(stream != null)
				stream.addLivePacketListener(sourcePacketListener);
		}
		
	}
	
	public static final String MODULE_NAME = "ModuleDuplicateStreams";
	public static final String PROP_NAME_PREFIX = "duplicateStreams";
	private static final int DELAYED_MAX_SIZE = 100;
	
	private String streamNames = "*";
	private String targetVHostName = IVHost.VHOST_DEFAULT;
	private String targetAppName = "live/_definst_";
	private String streamNameSuffix = "_dest";
	
	private IApplicationInstance appInstance = null;
	private WMSLogger logger = null;
	
	private SourcePacketListener sourcePacketListener = new SourcePacketListener();
	private SourceStreamNotify sourceStreamNotify = new SourceStreamNotify();
	private TargetAppInstanceNotify targetAppInstanceNotify = new TargetAppInstanceNotify();
	private Map<IMediaStream, List<AMFPacket>> delayedPackets = new HashMap<IMediaStream, List<AMFPacket>>();
	private long restartTimeout = 10000l;
	private boolean doRestarts = true;
	
	
	private Object lock = new Object();

	public void onAppCreate(IApplicationInstance appInstance)
	{
		Map<String, String> envMap = new HashMap<String, String>();
		
		envMap.put("com.wowza.wms.context.VHost", appInstance.getVHost().getName());
		envMap.put("com.wowza.wms.context.Application", appInstance.getApplication().getName());
		envMap.put("com.wowza.wms.context.ApplicationInstance", appInstance.getName());
				
		this.appInstance = appInstance;
		this.logger = WMSLoggerFactory.getLoggerObj(appInstance);
		WMSProperties props = appInstance.getProperties();
		this.streamNames = SystemUtils.expandEnvironmentVariables(props.getPropertyStr(PROP_NAME_PREFIX + "StreamNames", this.streamNames), envMap);
		this.targetVHostName = SystemUtils.expandEnvironmentVariables(props.getPropertyStr(PROP_NAME_PREFIX + "TargetVHostName", this.targetVHostName), envMap);
		this.targetAppName = SystemUtils.expandEnvironmentVariables(props.getPropertyStr(PROP_NAME_PREFIX + "TargetAppName", this.targetAppName), envMap);
		this.doRestarts = props.getPropertyBoolean(PROP_NAME_PREFIX + "DoRestarts", this.doRestarts);
		this.restartTimeout = props.getPropertyLong(PROP_NAME_PREFIX + "RestartTimeout", this.restartTimeout);
		
		this.streamNameSuffix = SystemUtils.expandEnvironmentVariables(props.getPropertyStr(PROP_NAME_PREFIX + "StreamNameSuffix", this.streamNameSuffix), envMap);
		
		logger.info(MODULE_NAME + ".onAppCreate [" + appInstance.getContextStr() + ", Build #11, streamNames: " + streamNames + ", targetVHost: " + targetVHostName + ", targetAppName: " + targetAppName + ", streamNameSuffix: " + streamNameSuffix + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
	}

	public void onAppStop(IApplicationInstance appInstance)
	{
		String appName = targetAppName;
		
		String[] context = targetAppName.split("/");
		if(context.length >= 1)
			appName = context[0];
		IVHost targetVHost = VHostSingleton.getInstance(targetVHostName);
		if(targetVHost != null && targetVHost.isApplicationLoaded(appName))
		{
			targetVHost.getApplication(appName).removeApplicationInstanceListener(targetAppInstanceNotify);
		}
	}

	public void onStreamCreate(IMediaStream stream)
	{
		stream.addClientListener(this.sourceStreamNotify);
		stream.addLivePacketListener(this.sourcePacketListener);
	}

	public void onStreamDestroy(IMediaStream stream)
	{
		synchronized(lock)
		{
			stream.removeClientListener(this.sourceStreamNotify);
			stream.removeLivePacketListener(this.sourcePacketListener);
			delayedPackets.remove(stream);
		}
	}

	private void doRestart(String streamName)
	{
		Timer t = new Timer();
		t.schedule(new RestartTask(streamName), restartTimeout);
	}
}
