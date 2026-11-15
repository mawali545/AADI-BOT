package com.aadi.bot;

/**
 * Shared entry-quality gate used by both the 10-second forecast engine and
 * the live 5-second execution confirmer. It separates broad trend from
 * immediate entry direction and blocks confirmed counter-momentum/exhaustion.
 */
final class EntryQualityGate {
    static final class Decision {
        final boolean blocked;
        final int counterVotes;
        final double normalizedFastMove;
        final String reason;
        Decision(boolean b,int v,double f,String r) {
            blocked=b; counterVotes=v; normalizedFastMove=f; reason=r;
        }
    }

    private EntryQualityGate() {}

    static Decision evaluate(int side, double atr, double fastMove,
            double slope5, double slope12, double recentBias, double reversalBias,
            double flow, double pressure, double rsi7, double rsi7Delta) {
        if (side == 0) return new Decision(false,0,Double.NaN,"no directional candidate");
        double fast=Double.isFinite(atr)&&atr>0&&Double.isFinite(fastMove)
                ? fastMove/atr : Double.NaN;
        boolean counterFast=side>0 ? finite(fast)&&fast < -0.04
                : finite(fast)&&fast > 0.04;
        boolean counterSlope=side>0 ? finite(slope5)&&slope5 < -0.12
                : finite(slope5)&&slope5 > 0.12;
        boolean counterRecent=side>0 ? finite(recentBias)&&recentBias < -0.10
                : finite(recentBias)&&recentBias > 0.10;
        boolean counterReversal=side>0 ? finite(reversalBias)&&reversalBias < -0.14
                : finite(reversalBias)&&reversalBias > 0.14;
        boolean counterFlow=side>0
                ? ((finite(flow)&&flow < -0.12)||(finite(pressure)&&pressure < -0.14))
                : ((finite(flow)&&flow > 0.12)||(finite(pressure)&&pressure > 0.14));
        boolean rsiTurning=side>0
                ? finite(rsi7)&&rsi7>=70.0&&finite(rsi7Delta)&&rsi7Delta<=-1.0
                : finite(rsi7)&&rsi7<=30.0&&finite(rsi7Delta)&&rsi7Delta>=1.0;
        int votes=(counterFast?1:0)+(counterSlope?1:0)+(counterRecent?1:0)
                +(counterReversal?1:0)+(counterFlow?1:0)+(rsiTurning?1:0);

        // A strong opposing short slope is itself meaningful, not diluted by
        // a slow trend that may still point in the old direction.
        boolean strongCounterSlope=counterSlope&&Math.abs(slope5)>=0.45;
        // At RSI extremes, even a modest opposing fast move is a concrete
        // exhaustion/recovery warning; RSI alone is never enough to veto.
        boolean extremeAndRecovering=counterFast&&finite(rsi7)
                &&(side<0?rsi7<=30.0:rsi7>=70.0);
        // Avoid continuing to chase a stretched move when the oscillator is
        // extreme and price no longer has strong same-side acceleration.
        boolean extremeWeakContinuation=finite(rsi7)&&finite(fast)&&finite(slope5)
                &&(side<0
                    ? rsi7<=22.0 && !(fast < -0.18 && slope5 < -0.12 && finite(slope12)&&slope12 < -0.08
                        && finite(reversalBias)&&reversalBias < -0.18)
                    : rsi7>=78.0 && !(fast > 0.18 && slope5 > 0.12 && finite(slope12)&&slope12 > 0.08
                        && finite(reversalBias)&&reversalBias > 0.18));
        // A counter fast move plus absent/weak recent confirmation is a late
        // entry warning even when the long trend remains aligned.
        boolean fastAgainstWeakRecent=counterFast&&finite(recentBias)
                &&(side>0?recentBias<=0.08:recentBias>=-0.08);

        boolean blocked=strongCounterSlope
                || extremeAndRecovering
                || extremeWeakContinuation
                || fastAgainstWeakRecent
                || (votes>=2&&(counterFast||counterSlope||counterRecent||counterReversal));
        String reason=blocked
                ? "entry-time counter-momentum/exhaustion conflict (votes "+votes
                    +", fast/ATR "+(Double.isFinite(fast)?String.format(java.util.Locale.US,"%+.3f",fast):"n/a")
                    +", slope5 "+(Double.isFinite(slope5)?String.format(java.util.Locale.US,"%+.3f",slope5):"n/a")
                    +", RSI7 "+(Double.isFinite(rsi7)?String.format(java.util.Locale.US,"%.1f",rsi7):"n/a")+")"
                : "entry momentum acceptable";
        return new Decision(blocked,votes,fast,reason);
    }

    private static boolean finite(double v) { return Double.isFinite(v); }
}
