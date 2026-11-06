package dev.bedrockadder.cli.nexo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NexoBlockStatesTest {

    @Test
    void noteBlock() {
        assertEquals("minecraft:note_block[instrument=harp,note=1,powered=false]", NexoBlockStates.state("NOTEBLOCK", 1));
        assertEquals("minecraft:note_block[instrument=harp,note=0,powered=true]", NexoBlockStates.state("noteblock", 25));
        assertEquals("minecraft:note_block[instrument=basedrum,note=0,powered=false]", NexoBlockStates.state("NOTEBLOCK", 50));
        // 1149 is Nexo's documented maximum: index 22 (custom_head), note 24, powered
        assertEquals("minecraft:note_block[instrument=custom_head,note=24,powered=true]", NexoBlockStates.state("NOTEBLOCK", 1149));
        assertNull(NexoBlockStates.state("NOTEBLOCK", 50 * NexoBlockStates.INSTRUMENTS.size()));
    }

    @Test
    void stringAndChorus() {
        assertEquals("minecraft:tripwire[attached=false,disarmed=false,east=false,north=true,powered=false,south=false,west=false]",
                NexoBlockStates.state("STRINGBLOCK", 1));
        assertEquals("minecraft:tripwire[attached=true,disarmed=true,east=true,north=true,powered=true,south=true,west=true]",
                NexoBlockStates.state("STRINGBLOCK", 127));
        assertEquals("minecraft:chorus_plant[down=true,east=false,north=false,south=false,up=true,west=false]",
                NexoBlockStates.state("CHORUSBLOCK", 48));
        assertNull(NexoBlockStates.state("CHORUSBLOCK", 64));
        assertNull(NexoBlockStates.state("FURNITURE", 1));
    }
}
