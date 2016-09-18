package act.data;

import act.app.ActionContext;
import org.apache.commons.codec.net.URLCodec;
import org.osgl.exception.UnexpectedException;
import org.osgl.http.H;
import org.osgl.mvc.result.ErrorResult;
import org.osgl.mvc.result.Result;
import org.osgl.util.C;
import org.osgl.util.FastStr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// Disclaim the source code is copied from Play!Framework 1.3
public class UrlEncodedParser extends RequestBodyParser {
    
    boolean forQueryString = false;

    @Override
    public Map<String, CharSequence[]> parse(ActionContext context) {
        H.Request request = context.req();
        // Encoding is either retrieved from contentType or it is the default encoding
        final String encoding = request.characterEncoding();
        InputStream is = request.inputStream();
        try {
            Map<CharSequence, CharSequence[]> params = new LinkedHashMap<CharSequence, CharSequence[]>();
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) > 0) {
                os.write(buffer, 0, bytesRead);
            }

            FastStr data = FastStr.of(os.toByteArray(), encoding);
            if (data.length() == 0) {
                //data is empty - can skip the rest
                return new HashMap<String, CharSequence[]>(0);
            }

            // check if data is in JSON format
            if (data.startsWith("{") && data.endsWith("}") || data.startsWith("[") && data.endsWith("]")) {
                return C.map(ActionContext.REQ_BODY, new String[]{data});
            }

            // data is o the form:
            // a=b&b=c%12...

            // Let us lookup in two phases - we wait until everything is parsed before
            // we decoded it - this makes it possible for use to look for the
            // special _charset_ param which can hold the charset the form is encoded in.
            //
            // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
            // https://bugzilla.mozilla.org/show_bug.cgi?id=18643
            //
            // NB: _charset_ must always be used with accept-charset and it must have the same value

            C.List<FastStr> keyValues = data.split("&");

            int httpMaxParams = context.app().config().httpMaxParams();
            // to prevent the server from being vulnerable to POST hash collision DOS-attack (Denial of Service through hash table multi-collisions),
            // we should by default not lookup the params into HashMap if the count exceeds a maximum limit
            if (httpMaxParams != 0 && keyValues.size() > httpMaxParams) {
                logger.warn("Number of request parameters %d is higher than maximum of %d, aborting. Can be configured using 'act.http.params.max'", keyValues.length, httpMaxParams);
                throw new ErrorResult(H.Status.valueOf(413)); //413 Request Entity Too Large
            }

            for (FastStr keyValue : keyValues) {
                // split this key-value on the first '='
                int i = keyValue.indexOf('=');
                FastStr key;
                FastStr value = null;
                if (i > 0) {
                    key = keyValue.substr(0, i).copy();
                    value = keyValue.substr(i + 1).copy();
                } else {
                    key = keyValue;
                }
                if (key.length() > 0) {
                    MapUtil.mergeValueInMap(params, key, value);
                }
            }

            // Second phase - look for _charset_ param and do the encoding
            CharSequence charset = encoding;
            if (params.containsKey("_charset_")) {
                // The form contains a _charset_ param - When this is used together
                // with accept-charset, we can use _charset_ to extract the encoding.
                // PS: When rendering the view/form, _charset_ and accept-charset must be given the
                // same value - since only Firefox and sometimes IE actually sets it when Posting
                CharSequence providedCharset = params.get("_charset_")[0];
                // Must be sure the providedCharset is a valid encoding..
                try {
                    "test".getBytes(providedCharset.toString());
                    charset = providedCharset; // it works..
                } catch (Exception e) {
                    logger.debug("Got invalid _charset_ in form: " + providedCharset);
                    // lets just use the default one..
                }
            }

            // We're ready to decode the params
            Map<CharSequence, CharSequence[]> decodedParams = new LinkedHashMap<CharSequence, CharSequence[]>(params.size());
            URLCodec codec = new URLCodec();
            String charsetStr = charset.toString();
            for (Map.Entry<CharSequence, CharSequence[]> e : params.entrySet()) {
                CharSequence key = e.getKey();
                try {
                    key = codec.decode(e.getKey().toString(), charsetStr);
                } catch (Throwable z) {
                    // Nothing we can do about, ignore
                }
                for (CharSequence value : e.getValue()) {
                    try {
                        MapUtil.mergeValueInMap(decodedParams, key, (value == null ? null : codec.decode(value.toString(), charsetStr)));
                    } catch (Throwable z) {
                        // Nothing we can do about, lets fill in with the non decoded value
                        MapUtil.mergeValueInMap(decodedParams, key, value);
                    }
                }
            }

            // add the complete body as a parameters
            if (!forQueryString) {
                decodedParams.put(ActionContext.REQ_BODY, new String[]{data});
            }

            return decodedParams;
        } catch (Result s) {
            // just pass it along
            throw s;
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

}
