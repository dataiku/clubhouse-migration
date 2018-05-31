package com.dataiku.clubhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class GithubMigrationTest {

    @Test
    void shouldPostProcessSimpleImage() {
        String actual = GithubMigration.postProcessImages("This is on my local instance, and here is where the folder lives:" +
                "<img width=\"836\" alt=\"screen shot 2018-04-25 at 5 11 43 pm\" src=\"https://user-images.githubusercontent.com/22987725/39273087-d1e6cc2e-48ab-11e8-83cf-c4a26f37488a.png\">" +
                "Thanks!");
        String expected = "This is on my local instance, and here is where the folder lives:" +
                "![screen shot 2018-04-25 at 5 11 43 pm](https://user-images.githubusercontent.com/22987725/39273087-d1e6cc2e-48ab-11e8-83cf-c4a26f37488a.png)" +
                "Thanks!";
        assertEquals(expected, actual);
    }

}