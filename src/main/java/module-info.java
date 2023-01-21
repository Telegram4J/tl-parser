import com.fasterxml.jackson.databind.Module;
import telegram4j.tl.json.TlModule;

module telegram4j.tl {
	requires io.netty.buffer;
	requires reactor.core;
	requires com.fasterxml.jackson.databind;

	requires static telegram4j.tl.parser;

	exports telegram4j.tl;
	exports telegram4j.tl.account;
	exports telegram4j.tl.api;
	exports telegram4j.tl.auth;
	exports telegram4j.tl.channels;
	exports telegram4j.tl.contacts;
	exports telegram4j.tl.help;
	exports telegram4j.tl.json;
	exports telegram4j.tl.messages;
	exports telegram4j.tl.mtproto;
	exports telegram4j.tl.payments;
	exports telegram4j.tl.phone;
	exports telegram4j.tl.photos;
	exports telegram4j.tl.request;
	exports telegram4j.tl.request.account;
	exports telegram4j.tl.request.auth;
	exports telegram4j.tl.request.bots;
	exports telegram4j.tl.request.channels;
	exports telegram4j.tl.request.contacts;
	exports telegram4j.tl.request.folders;
	exports telegram4j.tl.request.help;
	exports telegram4j.tl.request.langpack;
	exports telegram4j.tl.request.messages;
	exports telegram4j.tl.request.mtproto;
	exports telegram4j.tl.request.payments;
	exports telegram4j.tl.request.phone;
	exports telegram4j.tl.request.photos;
	exports telegram4j.tl.request.stats;
	exports telegram4j.tl.request.stickers;
	exports telegram4j.tl.request.updates;
	exports telegram4j.tl.request.upload;
	exports telegram4j.tl.request.users;
	exports telegram4j.tl.stats;
	exports telegram4j.tl.stickers;
	exports telegram4j.tl.storage;
	exports telegram4j.tl.updates;
	exports telegram4j.tl.upload;
	exports telegram4j.tl.users;
	provides Module with TlModule;
}
