package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Declares tools that talk to the user instead of the device.
 *
 * Unlike the other catalogs this one has no executor here: the call is handled by the run layer,
 * which emits the question to the entry surface and blocks until the user answers or the wait
 * times out. Only the schema lives here so the catalog stays free of Android/runtime dependencies.
 */
internal object AgentInteractionToolCatalog {
    const val TOOL_NAME = "ask_user"

    const val MAX_QUESTION_CHARS = 500
    const val MAX_OPTIONS = 6
    const val MAX_OPTION_CHARS = 80

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = TOOL_NAME,
                description = "Ask the user a question and wait for the answer. Use it only when the " +
                    "decision genuinely belongs to the user: an ambiguous goal, an irreversible or " +
                    "risky step that needs confirmation, or information you cannot obtain or infer " +
                    "yourself. Do not use it for politeness, for guesses you can verify with a tool, " +
                    "or when the user already told you. Give 2-6 short options when the choices are " +
                    "enumerable; the user can always type a free-form answer instead. The call blocks " +
                    "until the user answers or the wait times out; on timeout you receive ok=false with " +
                    "code=USER_NO_ANSWER, so continue with the safest default or report the blocker.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "question",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", MAX_QUESTION_CHARS)
                                    .put(
                                        "description",
                                        "The question itself, one sentence naming the decision to make.",
                                    ),
                            )
                            .put(
                                "options",
                                JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", MAX_OPTIONS)
                                    .put(
                                        "description",
                                        "Candidates when the choice is enumerable; omit or leave empty " +
                                            "to require a free-form answer.",
                                    )
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", MAX_OPTION_CHARS),
                                    ),
                            )
                            .put(
                                "multi_select",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put(
                                        "description",
                                        "Whether several options may be picked at once; default false.",
                                    ),
                            )
                            .put(
                                "allow_freeform",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put(
                                        "description",
                                        "Whether the user may answer by typing instead of picking; " +
                                            "default true.",
                                    ),
                            ),
                    )
                    .put("required", JSONArray().put("question"))
                    .put("additionalProperties", false),
            ),
        )
    }
}
