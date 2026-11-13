package com.aadi.bot;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class OrderBookSnapshotTest {
    @Test public void acceptsTwoSidedPriceAndSizeLevelsAndBracketsQuote() throws Exception {
        JSONObject root=new JSONObject()
                .put("bids",new JSONArray().put(new JSONArray().put(1.0990).put(120))
                        .put(new JSONArray().put(1.0980).put(80)))
                .put("asks",new JSONArray().put(new JSONArray().put(1.1010).put(100))
                        .put(new JSONArray().put(1.1020).put(70)));
        OrderBookSnapshot book=OrderBookSnapshot.parse(root);
        assertNotNull(book);
        assertEquals(2,book.bidLevels.size());
        assertEquals(2,book.askLevels.size());
        assertTrue(book.matchesQuote(1.1000));
        assertFalse(book.matchesQuote(1.1020));
    }

    @Test public void rejectsOneSidedOrCrossedBook() throws Exception {
        JSONObject oneSide=new JSONObject().put("bids",
                new JSONArray().put(new JSONArray().put(1.0990).put(100)));
        assertNull(OrderBookSnapshot.parse(oneSide));
        JSONObject crossed=new JSONObject()
                .put("bids",new JSONArray().put(new JSONArray().put(1.1020).put(100)))
                .put("asks",new JSONArray().put(new JSONArray().put(1.1010).put(100)));
        assertNull(OrderBookSnapshot.parse(crossed));
    }
}
