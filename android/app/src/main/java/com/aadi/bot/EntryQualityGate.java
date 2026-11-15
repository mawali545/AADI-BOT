package com.aadi.bot;

/** Shared live-entry gate for the 10-second forecast and execution confirmer. */
final class EntryQualityGate {
    static final class Decision {
        final boolean blocked;
        final int counterVotes;
        final double normalizedFastMove;
        final String reason;
        Decision(boolean b,int v,double f,String r){blocked=b;counterVotes=v;normalizedFastMove=f;reason=r;}
    }
    private EntryQualityGate() {}

    static Decision evaluate(int side,double atr,double fastMove,double slope5,double slope12,
            double recentBias,double reversalBias,double flow,double pressure,double rsi7,double rsi7Delta) {
        return evaluate(side,atr,fastMove,slope5,slope12,recentBias,reversalBias,flow,pressure,
                rsi7,rsi7Delta,Double.NaN,false,false);
    }

    /**
     * Distance is measured from current price to the opposing S/R level in ATR units.
     * A breakout only bypasses the proximity caution when the close has cleared the
     * level with a decisive candle body/close location.
     */
    static Decision evaluate(int side,double atr,double fastMove,double slope5,double slope12,
            double recentBias,double reversalBias,double flow,double pressure,double rsi7,double rsi7Delta,
            double opposingLevelDistanceAtr,boolean confirmedBreakout,boolean fadingOrConflictingAtLevel) {
        if(side==0)return new Decision(false,0,Double.NaN,"no directional candidate");
        double fast=finite(atr)&&atr>0&&finite(fastMove)?fastMove/atr:Double.NaN;
        boolean counterFast=side>0?finite(fast)&&fast< -0.04:finite(fast)&&fast>0.04;
        boolean counterSlope=side>0?finite(slope5)&&slope5< -0.12:finite(slope5)&&slope5>0.12;
        boolean counterRecent=side>0?finite(recentBias)&&recentBias< -0.10:finite(recentBias)&&recentBias>0.10;
        boolean counterReversal=side>0?finite(reversalBias)&&reversalBias< -0.14:finite(reversalBias)&&reversalBias>0.14;
        boolean counterFlow=side>0?((finite(flow)&&flow< -0.12)||(finite(pressure)&&pressure< -0.14))
                :((finite(flow)&&flow>0.12)||(finite(pressure)&&pressure>0.14));
        boolean rsiTurning=side>0?finite(rsi7)&&rsi7>=70&&finite(rsi7Delta)&&rsi7Delta<=-1
                :finite(rsi7)&&rsi7<=30&&finite(rsi7Delta)&&rsi7Delta>=1;
        int votes=(counterFast?1:0)+(counterSlope?1:0)+(counterRecent?1:0)
                +(counterReversal?1:0)+(counterFlow?1:0)+(rsiTurning?1:0);

        boolean strongCounterSlope=counterSlope&&Math.abs(slope5)>=0.45;
        boolean extremeAndRecovering=counterFast&&finite(rsi7)&&(side<0?rsi7<=30:rsi7>=70);
        boolean extremeWeakContinuation=finite(rsi7)&&finite(fast)&&finite(slope5)
                &&(side<0?rsi7<=22&&!(fast< -0.18&&slope5< -0.12&&finite(slope12)&&slope12< -0.08)
                :rsi7>=78&&!(fast>0.18&&slope5>0.12&&finite(slope12)&&slope12>0.08));
        boolean fastAgainstWeakRecent=counterFast&&finite(recentBias)
                &&(side>0?recentBias<=0.08:recentBias>=-0.08);

        boolean nearOpposingLevel=finite(opposingLevelDistanceAtr)
                &&opposingLevelDistanceAtr>=-0.03&&opposingLevelDistanceAtr<=0.18;
        // Do not reject RSI extremes by themselves. Require proximity to a barrier
        // plus weakening/conflicting entry evidence; a decisive breakout is exempt.
        boolean levelExhaustion=nearOpposingLevel&&!confirmedBreakout&&fadingOrConflictingAtLevel;
        boolean trendHorizonConflict=finite(slope5)&&finite(slope12)
                &&(side>0?slope5>0.12&&slope12< -0.10:slope5< -0.12&&slope12>0.10)
                &&nearOpposingLevel&&!confirmedBreakout;

        boolean blocked=strongCounterSlope||extremeAndRecovering||extremeWeakContinuation
                ||fastAgainstWeakRecent||levelExhaustion||trendHorizonConflict
                ||(votes>=2&&(counterFast||counterSlope||counterRecent||counterReversal));
        String reason=blocked?"entry gate blocked: "+(levelExhaustion?"opposing S/R proximity + fading/reversal risk"
                :trendHorizonConflict?"short/medium trend conflict at S/R"
                :"counter-momentum/exhaustion conflict")
                +" (votes "+votes+", fast/ATR "+fmt(fast)+", slope5 "+fmt(slope5)
                +", slope12 "+fmt(slope12)+", S/R distance "+fmt(opposingLevelDistanceAtr)+" ATR, RSI7 "+fmt(rsi7)+")"
                :"entry momentum and location acceptable";
        return new Decision(blocked,votes,fast,reason);
    }
    private static String fmt(double v){return finite(v)?String.format(java.util.Locale.US,"%+.3f",v):"n/a";}
    private static boolean finite(double v){return Double.isFinite(v);}
}
