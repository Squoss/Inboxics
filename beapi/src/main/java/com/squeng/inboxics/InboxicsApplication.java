/*
 * The MIT License
 *
 * Copyright (c) 2024 Squeng AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.squeng.inboxics;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyBuilder;
import net.fortuna.ical4j.model.component.CalendarComponent;

@SpringBootApplication
public class InboxicsApplication
		implements CommandLineRunner {

	private static Logger logger = LoggerFactory.getLogger(InboxicsApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(InboxicsApplication.class, args);
	}

	private String wrap(String vevent) {
		return """
				BEGIN:VCALENDAR
				PRODID:Inboxics
				METHOD:REQUEST
				VERSION:2.0
				""" + vevent.trim() + """
				END:VCALENDAR
				""";
	}

	@Value("${mailjet.apiKey}")
	private String mailjetApiKey;
	@Value("${mailjet.secretKey}")
	private String mailjetSecretKey;
	@Value("${mailjet.sender}")
	private String mailjetSender;

	@Override
	public void run(String... args) throws Exception {
		logger.info("Hello, world!");
		logger.info(mailjetSender);

		CompletableFuture<String> futureString = WebClient.create().get()
				.uri("https://ics.teamup.com/feed/ks3tmqwcrpt4vt5f3j/12969817.ics")
				.accept(new MediaType("text", "calendar"))
				.retrieve().bodyToMono(String.class).toFuture();

		CompletableFuture<List<CalendarComponent>> futureComponents = futureString.thenApply(s -> {
			try {
				CalendarBuilder builder = new CalendarBuilder();
				Calendar calendar = builder.build(new StringReader(s));
				logger.info(calendar.toString());
				return calendar.getComponents();
			} catch (Exception e) {
				e.printStackTrace(System.err);
				return Collections.emptyList();
			}
		});

		futureComponents.thenAccept(cs -> {
			cs.forEach(c -> {
				if ("VEVENT".equalsIgnoreCase(c.getName())) {
					logger.info(c.toString());
					c.replace(new PropertyBuilder().name(Property.CLASS).value("PRIVATE").build());
					c.replace(new PropertyBuilder().name(Property.METHOD).value("REQUEST").build());
					c.replace(new PropertyBuilder().name(Property.STATUS).value("CONFIRMED").build());
					c.removeAll(Property.ATTENDEE);
					logger.info(c.toString());
					String attachment = Base64.getMimeEncoder()
							.encodeToString(wrap(c.toString()).getBytes(Charset.forName("UTF-8")))
							.replace(System.getProperty("line.separator"), "");
					logger.info(attachment);
					String message = """
							{
								"Messages":[
										{
												"From": {
													"Email": "fixadat@squeng.com",
													"Name": "Inboxics"
												},
												"To": [
														{
																"Email": "paul@squeng.com",
																"Name": "Paul"
														}
												],
												"Subject": "Your feed dates & times",
												"TextPart": "Dear Paul, the feed you've subscribed to features a new event.",
												"InlinedAttachments": [
														{
																"ContentType": "text/calendar; method=REQUEST; charset=UTF-8",
																"Filename": "yada.ics",
																"ContentID": "id1",
																"Base64Content": "yadabla"
														}
												]
										}
								]
							}
								"""
							.replace("yadabla", attachment);
					logger.info(message);
					WebClient.create().post()
							.uri("https://api.mailjet.com/v3.1/send")
							.header("Authorization",
									"Basic " + Base64.getEncoder()
											.encodeToString((mailjetApiKey + ":" + mailjetSecretKey)
													.getBytes(Charset.forName("UTF-8"))))
							.contentType(MediaType.APPLICATION_JSON)
							.bodyValue(message)
							.retrieve().bodyToMono(String.class).subscribe(System.out::println);
				}
			});
		});
	}
}
