import com.weiguang.drcontroller.hardware.InspectionFlow;
public class InspectionFlowCheck {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        InspectionFlow local = new InspectionFlow("local", false, 0);
        check(!local.acceptsXray()); check(!local.beginAnalysis());
        local.radarComplete(); check(local.beginAnalysis()); check(!local.beginAnalysis());
        InspectionFlow host = new InspectionFlow("host", true, 0);
        host.radarComplete(); check(!host.beginAnalysis()); check(!host.askOnTimeout(29999));
        check(host.askOnTimeout(30000)); host.continueWaiting(31000);
        check(!host.askOnTimeout(60999)); check(host.askOnTimeout(61000));
        host.xrayComplete(); check(!host.isAwaitingDecision()); check(host.beginAnalysis());
        InspectionFlow skip = new InspectionFlow("skip", true, 0);
        skip.radarComplete(); skip.askOnTimeout(30000); skip.skipXray(); check(skip.beginAnalysis());
        InspectionFlow stop = new InspectionFlow("cancel", true, 0);
        stop.cancel(); check(!stop.acceptsXray()); check(!stop.askOnTimeout(30001)); check(!stop.beginAnalysis());
        System.out.println("PASS inspection wait/resume/skip/cancel/offline checks");
    }
}
