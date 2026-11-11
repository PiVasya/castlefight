package dev.cf;

/** Side-effect-free geometry/streaming tests for CF 2.3.3. */
public final class CF23SelfTest {
    private CF23SelfTest() {}
    private static int passed;
    private static void verify(boolean ok, String description) {
        if(!ok) throw new AssertionError(description);
        passed++;
    }
    public static void main(String[] args) {
        var a = new CFWorldEdit.Cell(-3,8,5);
        var b = new CFWorldEdit.Cell(2,10,7);
        var r = CFWorldEdit.Region.between(a,b);
        verify(r.volume()==54,"region count");
        verify(r.chunkCount()==2,"negative chunk count");
        verify(r.wall(new CFWorldEdit.Cell(-3,9,6)),"wall x min");
        verify(r.wall(new CFWorldEdit.Cell(0,9,7)),"wall z max");
        verify(!r.wall(new CFWorldEdit.Cell(0,9,6)),"interior not a wall");
        verify(r.floor(new CFWorldEdit.Cell(0,8,6)),"floor minY");
        verify(!r.floor(new CFWorldEdit.Cell(0,10,6)),"not floor above minY");
        verify(CFWorldEdit.Region.between(b,a).equals(r),"selection symmetric");
        verify(CFWorldEdit.Region.between(a,a).volume()==1,"one block");
        verify(new CFWorldEdit.Region(0,0,0,15,0,15).chunkCount()==1,"one chunk");
        verify(new CFWorldEdit.Region(-1,0,-1,0,0,0).chunkCount()==4,"negative boundary");
        long cursorCount=0;
        var cursor=r.cursor();
        while(cursor.hasNext()){
            var cell=cursor.peek();
            verify(cell.y()>=r.minY()&&cell.y()<=r.maxY(),"cursor in range");
            cursor.advance();cursorCount++;
        }
        verify(cursorCount==54,"streamed region enumeration");
        verify(!cursor.hasNext(),"end of cursor");
        verify(cursor.position()==54,"end position");
        var beyondOldLimit=new CFWorldEdit.Region(0,8,0,2048,8,2048);
        beyondOldLimit.validate();
        verify(beyondOldLimit.volume()>16_384,"more than old block limit");
        verify(beyondOldLimit.chunkCount()>64,"more than old chunk limit");
        verify(beyondOldLimit.cursor().peek().equals(new CFWorldEdit.Cell(0,8,0)),"large cursor lazy");
        var huge=new CFWorldEdit.Region(-29_000_000,-64,-29_000_000,29_000_000,319,29_000_000);
        huge.validate();
        verify(huge.volume()>Integer.MAX_VALUE,"long region volume");
        verify(huge.chunkCount()>Integer.MAX_VALUE,"long chunk count");
        verify(huge.cursor().peek().equals(new CFWorldEdit.Cell(-29_000_000,-64,-29_000_000)),"huge region lazy");
        boolean bad=false;
        try {new CFWorldEdit.Region(0,-65,0,0,0,0).validate();}catch(IllegalArgumentException expected){bad=true;}
        verify(bad,"world height still enforced");
        System.out.println("CF23_SELF_TEST PASS ("+passed+" assertions) - unlimited selection and streaming");
    }
}
