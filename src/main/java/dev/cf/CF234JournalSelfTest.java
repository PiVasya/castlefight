package dev.cf;

import java.nio.file.Files;
import net.minestom.server.instance.block.Block;

/** Validates disk-journal encoding and block-state round trips, without running a game server. */
public final class CF234JournalSelfTest {
    private CF234JournalSelfTest(){}
    public static void main(String[] args) throws Exception {
        CFWorldEdit.Edit saved;
        try(var writer=new CFWorldEdit.JournalWriter()) {
            writer.append(new CFWorldEdit.Cell(-19,8,131),Block.STONE,Block.PURPLE_CARPET);
            writer.append(new CFWorldEdit.Cell(0,12,-3),Block.AIR,Block.OAK_PLANKS);
            writer.flush();
            saved=writer.edit();
        }
        try {
            if(Files.size(saved.file())!=40 || saved.count()!=2)
                throw new AssertionError("Journal size or count wrong");
            try(var reader=new CFWorldEdit.JournalReader(saved)) {
                var first=reader.read();
                if(!first.pos().equals(new CFWorldEdit.Cell(-19,8,131)) ||
                        Block.fromStateId(first.beforeId())!=Block.STONE ||
                        Block.fromStateId(first.afterId())!=Block.PURPLE_CARPET)
                    throw new AssertionError("First journal entry wrong");
                var second=reader.read();
                if(!second.pos().equals(new CFWorldEdit.Cell(0,12,-3)) ||
                        Block.fromStateId(second.beforeId())!=Block.AIR ||
                        Block.fromStateId(second.afterId())!=Block.OAK_PLANKS || reader.hasNext())
                    throw new AssertionError("Second journal entry wrong");
            }
            System.out.println("CF234_JOURNAL_TEST PASS (2 entries, disk backed, block state preserved)");
        }finally {Files.deleteIfExists(saved.file());}
    }
}
