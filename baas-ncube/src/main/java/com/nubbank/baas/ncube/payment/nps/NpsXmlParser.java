package com.nubbank.baas.ncube.payment.nps;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.StringReader;
import org.xml.sax.InputSource;

/**
 * Parses NPS XML responses (acmt.024, pacs.002) into Java models.
 * Phase 1B: DOM parser. Phase 2: same (real NPS returns same XML format).
 */
@Slf4j
@Component
public class NpsXmlParser {

    public Acmt024Response parseAcmt024(String xml) {
        try {
            Document doc = parseXml(xml);
            String msgId = text(doc, "MsgId");
            String orgMsgId = text(doc, "OrgnlMsgId");
            String acctNm = text(doc, "Nm");
            String bvn = text(doc, "IdValue");
            String tier = text(doc, "AccountTier");
            String vrfd = text(doc, "Vrfd");
            boolean verified = "true".equalsIgnoreCase(vrfd) || !acctNm.isEmpty();
            return new Acmt024Response(msgId, orgMsgId, msgId,
                acctNm, bvn, tier.isEmpty() ? 1 : Integer.parseInt(tier),
                1, verified);
        } catch (Exception ex) {
            log.error("Failed to parse acmt.024: {}", ex.getMessage());
            return new Acmt024Response("", "", "", "", "", 1, 1, false);
        }
    }

    public Pacs002Response parsePacs002(String xml) {
        try {
            Document doc = parseXml(xml);
            String msgId = text(doc, "MsgId");
            String orgMsgId = text(doc, "OrgnlMsgId");
            String txSts = text(doc, "TxSts");
            if (txSts.isEmpty()) txSts = text(doc, "GrpSts");
            String rjctRsn = text(doc, "Rsn");
            return new Pacs002Response(msgId, orgMsgId, txSts, rjctRsn.isEmpty() ? null : rjctRsn);
        } catch (Exception ex) {
            log.error("Failed to parse pacs.002: {}", ex.getMessage());
            return new Pacs002Response("", "", "RJCT", "PARSE_ERROR");
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder()
            .parse(new InputSource(new StringReader(xml)));
    }

    private String text(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", tagName);
        if (nodes.getLength() == 0) nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
    }
}
