package dev.cf;

import java.util.Properties;

/** Pure regressions; no live server data, sockets or timers. */
public final class CF263SelfTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void close(float a,float b,String why){check(Math.abs(a-b)<.00001f,why+": "+a+" != "+b);}
    public static void main(String[] args)throws Exception {
        float hp=6f;
        for(int i=0;i<10;i++)hp=CFHealthMath.canonical(hp-.6f);
        close(hp,0,"ten .6 pulses exactly kill 6 HP");
        hp=7;
        hp=CFHealthMath.canonical(hp-3);
        for(int i=0;i<6;i++)hp=CFHealthMath.canonical(hp-.6f);
        close(hp,.4f,"4 - 3.6 leaves .4, not a rounded zero");
        check(CFHealthMath.label(hp).equals("0.4"),"label displays fractional HP");
        close(CFHealthMath.canonical(.00000048f),0,"float dust removed at HP write");
        check(CFHealthMath.label(.001f).equals("0.001"),"smallest positive HP visible");
        check(CFHealthMath.label(10).equals("10"),"integer HP not cluttered");
        check(CFHealthMath.label(1.05f).equals("1.05"),"no trailing zeros");
        close(CFHealthMath.canonical(-4),0,"overkill clamps to zero");
        Properties p=new Properties();
        p.setProperty("ability.poison.chance","1");p.setProperty("ability.poison.damage","0.6");
        p.setProperty("ability.poison.duration-ms","3000");
        p.setProperty("ability.burn.chance","1");p.setProperty("ability.burn.damage","0.8");
        p.setProperty("ability.burn.duration-ms","3000");
        var s=CFAbilitySpecs.parse(p);
        check(s.poisonPulses()==3 && s.burn().pulses()==3,"poison + burn coexist");
        close(s.poisonDamage(),.6f,"poison own amount");close(s.burn().damage(),.8f,"burn independent amount");
        p.setProperty("ability.burn.duration-ms","1500");
        boolean bad=false;try{CFAbilitySpecs.parse(p);}catch(java.io.IOException e){bad=true;}
        check(bad,"reject truncated burn interval");
        System.out.println("[CF263] PASS "+checks+" pure regressions");
    }
}
