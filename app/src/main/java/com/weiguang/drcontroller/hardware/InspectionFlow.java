package com.weiguang.drcontroller.hardware;

/** Pure workflow state. Continuing a wait never requests another exposure. */
public final class InspectionFlow {
    public static final long XRAY_WAIT_MS = 30_000L;
    public final String caseId;
    private boolean needXray, radarDone, xrayDone, asking, finished;
    private long deadline;
    public InspectionFlow(String caseId, boolean needXray, long now) {
        this.caseId = caseId; this.needXray = needXray; deadline = now + XRAY_WAIT_MS;
    }
    public boolean acceptsXray() { return needXray && !xrayDone && !finished; }
    public void radarComplete() { radarDone = true; }
    public void xrayComplete() { xrayDone = true; asking = false; }
    public boolean askOnTimeout(long now) {
        if (!acceptsXray() || asking || now < deadline) return false;
        asking = true; return true;
    }
    public boolean isAwaitingDecision() { return asking && !finished; }
    public void continueWaiting(long now) { if (isAwaitingDecision()) { asking = false; deadline = now + XRAY_WAIT_MS; } }
    public void skipXray() { needXray = false; asking = false; }
    public boolean beginAnalysis() {
        if (finished || !radarDone || (needXray && !xrayDone)) return false;
        finished = true; return true;
    }
    public void cancel() { finished = true; asking = false; }
}
