import { useId, useMemo } from 'react';
import { bodyPath, type Phenotype } from '../lib/phenotype';
import { mulberry32 } from '../lib/hash';

interface Props {
  phenotype: Phenotype;
  size?: number;
  /** Uncommitted workspace changes render as a glitching avatar. */
  dirty?: boolean;
  className?: string;
}

/** Procedurally generated creature, rendered entirely from the phenotype (no image assets). */
export function MonsterAvatar({ phenotype, size = 120, dirty = false, className = '' }: Props) {
  const uid = useId().replace(/:/g, '');
  const { body, element, secondary, markings } = phenotype;
  const cx = 60;
  const cy = 62;
  const outline = useMemo(() => bodyPath(body, cx, cy), [body]);
  const top = cy - body.radius * body.squash;
  const bottom = cy + body.radius * body.squash;

  const marks = useMemo(() => {
    const rng = mulberry32(markings.seed);
    const out: JSX.Element[] = [];
    for (let i = 0; i < markings.count; i++) {
      const x = cx + (rng() - 0.5) * body.radius * 1.5;
      const y = cy + (rng() - 0.2) * body.radius * 1.1;
      if (markings.pattern === 'spots') {
        out.push(<circle key={i} cx={x} cy={y} r={1.5 + rng() * 3.5} fill={secondary.color} opacity={0.45} />);
      } else if (markings.pattern === 'stripes') {
        const yy = top + ((i + 1) / (markings.count + 1)) * (bottom - top);
        out.push(
          <path
            key={i}
            d={`M${cx - body.radius} ${yy} Q${cx} ${yy + (rng() - 0.5) * 10} ${cx + body.radius} ${yy}`}
            stroke={secondary.color}
            strokeWidth={2}
            fill="none"
            opacity={0.35}
          />,
        );
      } else if (markings.pattern === 'circuit') {
        const len = 6 + rng() * 10;
        out.push(
          <g key={i} stroke={secondary.color} strokeWidth={1.2} fill="none" opacity={0.6}>
            <path d={`M${x} ${y} h${len} v${len * 0.6}`} />
            <circle cx={x + len} cy={y + len * 0.6} r={1.4} fill={secondary.color} />
          </g>,
        );
      }
    }
    return out;
  }, [markings, body.radius, secondary.color, top, bottom]);

  const eyeY = cy - body.radius * body.squash * 0.18;
  const eyeXs =
    body.eyes === 1 ? [cx] : body.eyes === 2 ? [cx - body.radius * 0.36, cx + body.radius * 0.36] : [cx - 13, cx, cx + 13];

  const horns = Array.from({ length: body.horns }, (_, i) => {
    const spread = body.horns === 1 ? 0 : (i / (body.horns - 1) - 0.5) * body.radius * 1.2;
    const bx = cx + spread;
    const by = top + 4;
    const tilt = spread * 0.35;
    if (body.hornStyle === 'antenna') {
      return (
        <g key={i}>
          <path d={`M${bx} ${by} Q${bx + tilt} ${by - 14} ${bx + tilt * 1.6} ${by - 20}`} stroke={element.color} strokeWidth={1.8} fill="none" />
          <circle cx={bx + tilt * 1.6} cy={by - 20} r={3} fill={secondary.color} filter={`url(#glow-${uid})`} />
        </g>
      );
    }
    if (body.hornStyle === 'curl') {
      return (
        <path
          key={i}
          d={`M${bx - 3} ${by + 2} C${bx - 4 + tilt} ${by - 16} ${bx + 10 + tilt} ${by - 16} ${bx + 6 + tilt} ${by - 6}`}
          stroke={secondary.color}
          strokeWidth={3}
          strokeLinecap="round"
          fill="none"
        />
      );
    }
    return (
      <path key={i} d={`M${bx - 4} ${by + 3} L${bx + tilt} ${by - 15} L${bx + 4} ${by + 3}Z`} fill={secondary.color} opacity={0.9} />
    );
  });

  const feet = Array.from({ length: body.feet }, (_, i) => {
    const fx = cx + (body.feet === 1 ? 0 : (i / (body.feet - 1) - 0.5) * body.radius * 1.1);
    return <ellipse key={i} cx={fx} cy={bottom - 1} rx={5} ry={3.2} fill={element.color} opacity={0.85} />;
  });

  const mouthY = cy + body.radius * body.squash * 0.28;
  const mouth = {
    grin: <path d={`M${cx - 9} ${mouthY} Q${cx} ${mouthY + 8} ${cx + 9} ${mouthY}`} stroke="#0b0f19" strokeWidth={2.2} fill="none" strokeLinecap="round" />,
    fangs: (
      <g>
        <path d={`M${cx - 10} ${mouthY} Q${cx} ${mouthY + 6} ${cx + 10} ${mouthY}`} stroke="#0b0f19" strokeWidth={2.2} fill="none" strokeLinecap="round" />
        <path d={`M${cx - 5} ${mouthY + 2} l2 4 l2 -3.5Z M${cx + 1} ${mouthY + 2.5} l2 4 l2 -4.5Z`} fill="#f8fafc" />
      </g>
    ),
    o: <ellipse cx={cx} cy={mouthY + 1} rx={3.5} ry={4.5} fill="#0b0f19" />,
    line: <path d={`M${cx - 7} ${mouthY + 1} h14`} stroke="#0b0f19" strokeWidth={2.2} strokeLinecap="round" />,
  }[body.mouth];

  const creature = (
    <>
      {body.tail && (
        <path
          d={`M${cx + body.radius * 0.7} ${cy + body.radius * 0.4} q18 6 22 -12 q2 -6 -4 -6`}
          stroke={element.color}
          strokeWidth={4}
          strokeLinecap="round"
          fill="none"
        />
      )}
      {horns}
      {feet}
      <path d={outline} fill={`url(#body-${uid})`} stroke={element.color} strokeWidth={1.5} filter={`url(#glow-${uid})`} />
      <g clipPath={`url(#clip-${uid})`}>{marks}</g>
      {eyeXs.map((x, i) => (
        <g key={i}>
          <circle cx={x} cy={eyeY} r={body.eyeSize} fill="#f8fafc" />
          <circle cx={x + 1} cy={eyeY + 0.8} r={body.eyeSize * 0.52} fill="#0b0f19" />
          <circle cx={x + 2} cy={eyeY - 1.2} r={body.eyeSize * 0.2} fill="#ffffff" />
        </g>
      ))}
      {mouth}
    </>
  );

  return (
    <svg
      viewBox="0 0 120 120"
      width={size}
      height={size}
      className={className}
      role="img"
      aria-label={`${element.name}-type DAIMon avatar`}
    >
      <defs>
        <radialGradient id={`body-${uid}`} cx="40%" cy="35%" r="75%">
          <stop offset="0%" stopColor={element.color} stopOpacity={0.95} />
          <stop offset="70%" stopColor={element.color} stopOpacity={0.55} />
          <stop offset="100%" stopColor={secondary.color} stopOpacity={0.7} />
        </radialGradient>
        <radialGradient id={`aura-${uid}`} cx="50%" cy="55%" r="50%">
          <stop offset="0%" stopColor={element.color} stopOpacity={0.35} />
          <stop offset="100%" stopColor={element.color} stopOpacity={0} />
        </radialGradient>
        <filter id={`glow-${uid}`} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="1.8" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        <clipPath id={`clip-${uid}`}>
          <path d={outline} />
        </clipPath>
      </defs>
      <circle cx={cx} cy={cy} r={56} fill={`url(#aura-${uid})`} />
      {dirty ? (
        <>
          <g opacity={0.55} transform="translate(-2 0)" style={{ mixBlendMode: 'screen' }} className="animate-glitch">
            <path d={outline} fill="#ec4899" opacity={0.5} />
          </g>
          <g opacity={0.55} transform="translate(2 0)" style={{ mixBlendMode: 'screen' }}>
            <path d={outline} fill="#06b6d4" opacity={0.5} />
          </g>
          <g className="animate-float">{creature}</g>
        </>
      ) : (
        <g className="animate-float">{creature}</g>
      )}
    </svg>
  );
}
