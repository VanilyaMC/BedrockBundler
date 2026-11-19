# Third party notices

BedrockBundler itself is licensed under AGPL-3.0-only (see `LICENSE`). Parts of the BetterModel (`.bbmodel`)
conversion in `common/src/main/java/dev/bedrockbundler/common/model/` are derived from the following works.

## GeyserModelEngine

The resource pack generator for custom entities (`ModelPackGenerator`, `ShortHash`, the material template and the
bone/animation/tint property scheme) is ported from the Geyser extension of
[GeyserModelEngine](https://github.com/GeyserExtensionists/GeyserModelEngine) (classes `ResourcePackManager`,
`Entity`, `Geometry`, `Animation`, `AnimationController`, `RenderController`, `TranslucencySplitter`,
`ManualAnimator`, `Material`, `ShortHashUtil`, `BooleanPacker`), including the changes of the VanilyaMC fork.
`BbmodelReader` also follows the texture naming rule of its Blockbench packer.

MIT License

Copyright (c) 2024 zimzaza4 and GeyserModelEngine contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Blockbench

`BbmodelReader` reproduces the output format rules of [Blockbench](https://www.blockbench.net/)'s Bedrock model
and animation codec (mirrored X axis, rotation signs, flipped up and down face UVs, `catmullrom` keyframes,
loop modes). Only the documented behaviour is implemented; no Blockbench source code is included.
