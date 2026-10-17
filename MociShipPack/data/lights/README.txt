光源数据
id: 投射物ID（来自投射物文件）或武器ID（来自武器文件，适用于光束）。
type: 类型为「投射物（projectile）」或「光束（beam）」。
size: 投射物光源的尺寸。
intensity: 投射物光源的强度。
fadeout: 投射物开始消退时，光源随之淡出的时间。此值通常应与投射物文件的消退时间一致。
color: 投射物光源的颜色。
offset: 光源相对于投射物精灵图中心的垂直下移偏移量（常用于导弹类武器）。
hit size: 武器命中战斗实体时，闪光效果的尺寸。
hit intensity: 命中时的光源强度。
hit fadeout: 命中光源的淡出时间，通常应与投射物文件的消退时间（fadetime）匹配。
flash size: 投射物生成时（开火瞬间）枪口闪光光源的尺寸。
flash intensity: 枪口闪光的强度。
flash fadeout: 枪口闪光的消退时间。
flash color: 枪口闪光的颜色。
flash offset: 光源相对于精灵图中心的垂直偏移量（默认从炮管中心生成，通常无需修改）。
chance: 投射物生成时附带光源的概率。
fighter dim: 当光源来自战斗机或无人机时，是否减弱亮度（默认启用）。

纹理数据
id: 对应的 hullid（船体ID）、hullid_STYLE（船体风格ID）、weaponid（武器ID）、projectileid（投射物ID）或 STYLEID（风格ID）。
type: 类型包括「ship（舰船）」「turret（炮塔）」「turretbarrel（炮塔炮管）」「turretunder（炮塔底部）」「hardpoint（武器挂点）」「hardpointbarrel（挂点炮管）」「hardpointunder（挂点底部）」「missile（导弹）」「turretcoversmall（小型炮塔盖板）」「turretcovermedium（中型炮塔盖板）」「turretcoverlarge（大型炮塔盖板）」「hardpointcoversmall（小型挂点盖板）」「hardpointcovermedium（中型挂点盖板）」「hardpointcoverlarge（大型挂点盖板）」。
frame: 动态武器的动画帧数。
magnitude: 法线贴图的相对深度（默认1.0）。
map: 贴图类型，可选「material（材质）」「normal（法线）」「surface（表面）」。
path: 纹理图片的文件路径（例如：graphics/shaders/material/ships/apogee_cx_material.png）。
[文件内容结束]