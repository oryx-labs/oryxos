package io.oryxos.channel.mattermost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.InboundAttachment;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MattermostInboundMediaResolverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("metadata.files 解析 id / 文件名 / mime")
  void filesFromMetadata() throws Exception {
    var post =
        MAPPER.readTree(
            """
            {
              "file_ids": ["abc"],
              "metadata": {
                "files": [
                  {"id": "img1", "name": "a.png", "mime_type": "image/png", "extension": "png"},
                  {"id": "doc1", "name": "b.pdf", "mime_type": "application/pdf", "extension": "pdf"}
                ]
              }
            }
            """);
    List<MattermostInboundMediaResolver.FileSpec> files =
        MattermostInboundMediaResolver.filesFromPost(post);
    assertEquals(2, files.size());
    assertEquals("img1", files.get(0).id());
    assertEquals("b.pdf", files.get(1).name());
  }

  @Test
  @DisplayName("无 metadata 时回退 file_ids")
  void filesFromIds() throws Exception {
    var post = MAPPER.readTree("{\"file_ids\":[\"only1\",\"bad id\"]}");
    List<MattermostInboundMediaResolver.FileSpec> files =
        MattermostInboundMediaResolver.filesFromPost(post);
    assertEquals(1, files.size());
    assertEquals("only1", files.get(0).id());
  }

  @Test
  @DisplayName("按 mime / 扩展名分类图与 PDF")
  void classify() {
    assertEquals(
        InboundAttachment.TYPE_IMAGE,
        MattermostInboundMediaResolver.classifyType("image/jpeg", "x", "jpg"));
    assertEquals(
        InboundAttachment.TYPE_FILE,
        MattermostInboundMediaResolver.classifyType("application/pdf", "x.pdf", "pdf"));
    assertEquals(
        InboundAttachment.TYPE_IMAGE,
        MattermostInboundMediaResolver.classifyType("", "shot.PNG", ""));
    assertEquals(
        InboundAttachment.TYPE_VIDEO,
        MattermostInboundMediaResolver.classifyType("video/mp4", "a.mp4", "mp4"));
  }
}
