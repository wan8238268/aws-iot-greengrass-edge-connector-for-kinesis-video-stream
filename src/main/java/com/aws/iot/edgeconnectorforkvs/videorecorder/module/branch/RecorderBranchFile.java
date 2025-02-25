/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aws.iot.edgeconnectorforkvs.videorecorder.module.branch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.aws.iot.edgeconnectorforkvs.videorecorder.base.RecorderBranchBase;
import com.aws.iot.edgeconnectorforkvs.videorecorder.model.ContainerType;
import com.aws.iot.edgeconnectorforkvs.videorecorder.util.Config;
import com.aws.iot.edgeconnectorforkvs.videorecorder.util.GstDao;
import com.sun.jna.Pointer;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.PadProbeReturn;
import org.freedesktop.gstreamer.PadProbeType;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.event.EOSEvent;
import org.freedesktop.gstreamer.lowlevel.GPointer;
import org.freedesktop.gstreamer.lowlevel.GstAPI.GstCallback;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Data branch for file store.
 */
@Slf4j
public class RecorderBranchFile extends RecorderBranchBase {
    private static final long UNBIND_TIMEOUT_MS = 3000;
    private Element muxer;
    private Element fileSink;
    private Element splitMuxSink;
    private Pad fileSinkPad;
    private GstDao gstCore;
    private Pipeline pipeline;
    private String filePath;
    private ContainerType containerType;
    private String fileExtension;
    private HashMap<String, Object> propertySet;
    private String currentFilePath;
    @Getter(AccessLevel.PROTECTED)
    private Pad.PROBE padEosProbe;
    private CountDownLatch deattachCnt;

    /**
     * Interface for updating new file path.
     */
    public interface LocCallback extends GstCallback {
        /**
         * Callback for updating new file path.
         *
         * @param splitmux splitmuxsink
         * @param fragmentId fragment Id
         * @param uData user data
         * @return gchar pointer to the next location string
         */
        Pointer callback(Element splitmux, long fragmentId, GPointer uData);
    }

    /**
     * RecorderBranchFile constructor.
     *
     * @param type multimedia container type
     * @param dao GStreamer data access object
     * @param pipeline GStreamer pipeline
     * @param filePath stored file prefix
     */
    public RecorderBranchFile(ContainerType type, GstDao dao, Pipeline pipeline, String filePath) {
        super(Config.FILE_PATH_CAPABILITY, dao, pipeline);
        this.gstCore = this.getGstCore();
        this.pipeline = this.getPipeline();
        this.filePath = filePath;
        this.containerType = type;
        this.fileExtension = this.getFileExtensionFromType(type);
        this.propertySet = new HashMap<>();
        this.currentFilePath = null;
    }

    /**
     * Get current file path.
     *
     * @return file path
     */
    public synchronized String getCurrentFilePath() {
        return this.currentFilePath;
    }

    private synchronized void setCurrentFilePath(String path) {
        this.currentFilePath = path;
    }

    /**
     * Set properties to the file branch.
     *
     * @param property property name
     * @param data value
     * @return true if the property is set successfully or anynced
     */
    public synchronized boolean setProperty(String property, Object data) {
        boolean ret = true;

        this.propertySet.put(property, data);

        if (this.splitMuxSink != null) {
            try {
                this.gstCore.setElement(this.splitMuxSink, property, data);
            } catch (IllegalArgumentException e) {
                log.error("Setting FileBranch fails for the invalid property {}.", property);
                this.propertySet.remove(property);
                ret = false;
            }
        } else {
            log.warn("The fileBranch will set properties later.");
        }

        return ret;
    }

    @Override
    protected synchronized void onBindBegin() {
        ArrayList<String> invalidProp = new ArrayList<>();

        log.debug("FileBranch onBind");

        this.muxer = this.getMuxerFromType(this.containerType, true);
        this.fileSink = this.gstCore.newElement("filesink");
        this.splitMuxSink = this.gstCore.newElement("splitmuxsink");

        this.gstCore.setElement(this.splitMuxSink, "muxer", this.muxer);
        this.gstCore.setElement(this.splitMuxSink, "sink", this.fileSink);
        this.gstCore.setAsStringElement(this.splitMuxSink, "location",
                this.filePath + "." + this.fileExtension);
        this.gstCore.setElement(this.splitMuxSink, "max-size-time",
                Config.DEFAULT_FILE_ROTATION_IN_NS);
        this.gstCore.setElement(this.splitMuxSink, "send-keyframe-requests", true);

        // Delayed property setting
        for (Map.Entry<String, Object> property : this.propertySet.entrySet()) {
            try {
                this.gstCore.setElement(this.splitMuxSink, property.getKey(), property.getValue());
            } catch (IllegalArgumentException e) {
                log.error("Setting FileBranch skips the invalid property {}.", property.getKey());
                invalidProp.add(property.getKey());
            }
        }
        for (String prop : invalidProp) {
            this.propertySet.remove(prop);
        }

        // Signals
        this.gstCore.connectElement(this.splitMuxSink, "format-location",
                (LocCallback) (elm, fId, uData) -> {
                    this.setCurrentFilePath(String.format("%s_%d.%s", filePath,
                            Instant.now().toEpochMilli(), fileExtension));
                    log.debug("LocCallback: " + this.getCurrentFilePath());
                    return this.gstCore.invokeGLibStrdup(this.getCurrentFilePath());
                });

        // Add EOS probe
        this.deattachCnt = new CountDownLatch(1);
        this.padEosProbe = (reqPad, info) -> {
            if (info.getEvent().getClass() == EOSEvent.class) {
                log.info("FileBranch sink reveices EOS.");
                this.deattachCnt.countDown();
            }
            return PadProbeReturn.REMOVE;
        };
        HashSet<PadProbeType> mask =
                new HashSet<>(Arrays.asList(PadProbeType.BLOCK, PadProbeType.EVENT_DOWNSTREAM));
        this.fileSinkPad = this.gstCore.getElementStaticPad(this.fileSink, "sink");
        this.gstCore.addPadProbe(this.fileSinkPad, mask, this.padEosProbe);

        // add elements
        this.gstCore.addPipelineElements(this.pipeline, this.splitMuxSink);

        this.gstCore.playElement(this.splitMuxSink);
    }

    @Override
    protected synchronized void onUnbindBegin() {
        log.info("FileBranch is waiting for EOS.");
        boolean isEos = false;

        try {
            isEos = this.deattachCnt.await(RecorderBranchFile.UNBIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("deattachCnt InterruptedException: {}", e.getMessage());
        }

        if (isEos) {
            log.info("FileBranch received EOS.");
        } else {
            log.warn("FileBranch does not receive EOS");
            this.gstCore.removePadProbe(this.fileSinkPad, this.padEosProbe);
            this.gstCore.sendElementEvent(this.splitMuxSink, this.gstCore.newEosEvent());
            try {
                TimeUnit.MILLISECONDS.sleep(RecorderBranchFile.UNBIND_TIMEOUT_MS);
            } catch (InterruptedException e) {
                log.error("FileBranch waits for new EOS error: {}.", e);
            }
        }
        this.deattachCnt = null;
    }

    @Override
    protected synchronized void onUnbindEnd() {
        log.info("FileBranch stops splitmuxsink");
        this.gstCore.stopElement(this.splitMuxSink);
        log.info("FileBranch removes splitmuxsink");
        this.gstCore.removePipelineElements(this.pipeline, this.splitMuxSink);
        log.info("FileBranch sets null");
        this.fileSinkPad = null;
        this.splitMuxSink = null;
        this.fileSink = null;
        this.muxer = null;
        this.currentFilePath = null;
    }

    @Override
    protected synchronized Pad getEntryAudioPad() {
        log.debug("New FileStoreBranch entry audio pad.");
        return this.gstCore.getElementRequestPad(this.splitMuxSink, "audio_%u");
    }

    @Override
    protected synchronized Pad getEntryVideoPad() {
        log.debug("New FileStoreBranch entry video pad.");
        return this.gstCore.getElementRequestPad(this.splitMuxSink, "video");
    }

    @Override
    protected synchronized void relEntryAudioPad(Pad pad) {
        log.debug("Rel FileStoreBranch entry audio pad.");
        this.gstCore.relElementRequestPad(this.splitMuxSink, pad);
    }

    @Override
    protected synchronized void relEntryVideoPad(Pad pad) {
        log.debug("Rel FileStoreBranch entry video pad.");
        this.gstCore.relElementRequestPad(this.splitMuxSink, pad);
    }
}
