package io.oryxos.channel.matrix;

import static io.oryxos.channel.matrix.MatrixChannelAdapter.inviteLooksDirect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatrixDirectRoomsTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final MatrixDirectRooms rooms = new MatrixDirectRooms();

  @Test
  @DisplayName("顶层 account_data m.direct 记房间，房间 account_data 不算")
  void topLevelDirectOnly() throws Exception {
    rooms.mergeFromSync(
        mapper.readTree(
            """
            {
              "account_data": {
                "events": [
                  {"type":"m.direct","content":{"@alice:hs":["!dm:hs"]}}
                ]
              },
              "rooms": {
                "join": {
                  "!room:hs": {
                    "account_data": {"events":[{"type":"m.tag"}]}
                  }
                }
              }
            }
            """));
    assertTrue(rooms.isDirect("!dm:hs"));
    assertFalse(rooms.isDirect("!room:hs"));
    assertEquals(1, rooms.size());
  }

  @Test
  @DisplayName("后续增量没有 m.direct 时仍记住")
  void persistsAcrossEmptySync() throws Exception {
    rooms.mergeFromSync(
        mapper.readTree(
            """
            {"account_data":{"events":[{"type":"m.direct","content":{"@a:hs":["!dm:hs"]}}]}}
            """));
    rooms.mergeFromSync(mapper.readTree("{\"next_batch\":\"s2\"}"));
    assertTrue(rooms.isDirect("!dm:hs"));
  }

  @Test
  @DisplayName("邀请 is_direct 记为私聊")
  void rememberInvite() throws Exception {
    rooms.remember("!newdm:hs");
    assertTrue(rooms.isDirect("!newdm:hs"));
    assertTrue(
        inviteLooksDirect(
            mapper.readTree(
                """
                {"invite_state":{"events":[
                  {"type":"m.room.member","content":{"membership":"invite","is_direct":true}}
                ]}}
                """)));
  }
}
