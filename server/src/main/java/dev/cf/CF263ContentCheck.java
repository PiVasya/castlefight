package dev.cf;
import java.nio.file.Path;
/** Validate live/custom content without imposing the bundled balance or exact mob count. */
public final class CF263ContentCheck {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);var registry=new CFContentRegistry(root);
        var specs=new CFAbilitySpecs(root.resolve("mobs"));
        for(var mob:registry.mobs())specs.get(mob.id());
        if(registry.mob("skeleton")!=null || registry.mob("zombie")!=null)
            throw new IllegalArgumentException("Retired prototype types must be migrated first");
        for(String id:new String[]{"grave_zombie","risen","banshee","acolyte","risen_arsonist"})
            if(registry.mob(id)==null)throw new IllegalArgumentException("Missing standard mob: "+id);
        if(!registry.mob("risen").behavior().equals("ranged_hold"))
            throw new IllegalArgumentException("Risen must be ranged_hold");
        System.out.println("[CF263 CONTENT] VALID mobs="+registry.mobs().size()+" towers="+registry.towers().size());
    }
}
