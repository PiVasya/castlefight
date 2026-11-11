package dev.cf;

/** Pure Java, runs without Minestom. Independent expected matrix from the image. */
public final class CF271DamageSelfTest {
    private static int checks;
    private static void check(boolean ok,String why) {
        checks++;if(!ok)throw new AssertionError(why);
    }
    private static void rejects(Runnable call,String why) {
        boolean rejected=false;
        try{call.run();}catch(IllegalArgumentException ex){rejected=true;}
        check(rejected,why);
    }
    public static void main(String[] args) {
        int[][] expected={{105,55,190,90,50},{105,190,90,55,40},
                          {105,90,55,190,40},{105,55,55,55,190},{105,100,100,100,100}};
        var types=CFDamageTable.Type.values();var armor=CFDamageTable.Armor.values();
        for(int t=0;t<5;t++)for(int a=0;a<5;a++)
            check(CFDamageTable.percent(types[t],armor[a])==expected[t][a],"image cell "+t+"/"+a);
        for(int raw=0;raw<=10000;raw++)for(int t=0;t<5;t++) {
            var hit=new CFDamageTable.Hit(types[t],raw);
            for(int a=0;a<5;a++) {
                int want=(int)((long)raw*expected[t][a]/100);
                check(hit.amount10(armor[a])==want,"one immutable armor result");
                check(CFDamageTable.scale10(want,65)==want*65/100,
                        "runtime post-armor effect uses integer math");
            }
        }
        int[] wolf={23,44,92};
        for(int damage:wolf)for(int a=0;a<5;a++) {
            var profile=new CFDamageTable.Profile(CFDamageTable.Type.PIERCING,armor[a],18,damage);
            check(profile.critical.amount10(armor[a])==damage*expected[1][a]/100,"fixed wolf bite");
        }
        var snowman=new CFDamageTable.Hit(CFDamageTable.Type.PIERCING,5);
        check(snowman.amount10(CFDamageTable.Armor.HEAVY)==2,"5 x 55 / 100 = 2, truncate");
        check(new CFDamageTable.Hit(CFDamageTable.Type.MAGIC,1)
                .amount10(CFDamageTable.Armor.FORTIFIED)==0,"no artificial min damage");
        var fixed=new CFDamageTable.Profile(CFDamageTable.Type.UNTYPED,CFDamageTable.Armor.NONE,30,30);
        check(fixed.normal==fixed.critical,"reuse equal crit table");
        for(var a:armor)check(fixed.normal.amount10(a)==30,"untyped is NOT chaos");
        for(var t:types)check(CFDamageTable.Type.parse(t.id)==t,"type roundtrip");
        for(var a:armor)check(CFDamageTable.Armor.parse(a.id)==a,"armor roundtrip");
        check(CFDamageTable.scale10(Integer.MAX_VALUE,100)==Integer.MAX_VALUE,"safe multiply via long");
        rejects(()->CFDamageTable.scale10(Integer.MAX_VALUE,190),"overflow rejected");
        rejects(()->new CFDamageTable.Hit(CFDamageTable.Type.CHAOS,-1),"negative damage rejected");
        rejects(()->CFDamageTable.Type.parse("typo"),"unknown type rejected");
        rejects(()->CFDamageTable.Armor.parse("iron"),"unknown armor rejected");
        System.out.println("[CF DAMAGE TABLE] PASS "+checks+" assertions; exact 5x5 matrix, int10, fixed crits, dynamic post-armor shields, zero, overflow");
    }
}
