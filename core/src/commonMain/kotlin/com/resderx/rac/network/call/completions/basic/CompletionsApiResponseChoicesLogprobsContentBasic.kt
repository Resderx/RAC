package com.resderx.rac.network.call.completions.basic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseChoicesLogprobsContentBasic {
    val token: String
    val logprob: Number
    val bytes: List<Int>?
    @SerialName("top_logprobs")
    val topLogprobs:CompletionsApiResponseChoicesLogprobsContentTopLogprobsBasic
}

/*
logprobs

object

nullable

required

该 choice 的对数概率信息。

content

object[]

nullable

required

一个包含输出 token 对数概率信息的列表。

Array [

token
string
required
输出的 token。

logprob
number
required
该 token 的对数概率。-9999.0 代表该 token 的输出概率极小，不在 top 20 最可能输出的 token 中。

bytes
integer[]
nullable
required
一个包含该 token UTF-8 字节表示的整数列表。一般在一个 UTF-8 字符被拆分成多个 token 来表示时有用。如果 token 没有对应的字节表示，则该值为 null。

top_logprobs

object[]

required

一个包含在该输出位置上，输出概率 top N 的 token 的列表，以及它们的对数概率。在罕见情况下，返回的 token 数量可能少于请求参数中指定的 top_logprobs 值。

Array [

token
string
required
输出的 token。

logprob
number
required
该 token 的对数概率。-9999.0 代表该 token 的输出概率极小，不在 top 20 最可能输出的 token 中。

bytes
integer[]
nullable
required
一个包含该 token UTF-8 字节表示的整数列表。一般在一个 UTF-8 字符被拆分成多个 token 来表示时有用。如果 token 没有对应的字节表示，则该值为 null。

]

]
 */